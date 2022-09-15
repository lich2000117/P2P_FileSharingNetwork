package comp90015.idxsrv.peer;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IndexElement;
import comp90015.idxsrv.textgui.ISharerGUI;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A Download Thread that asks connections from peers and write files
 * into local.
 * @author Chenghao Li
 *
 */
public class PeerDownloadThread extends Thread {
    private final String relativePathname;
    private final SearchRecord searchRecord;
    private final ConnectServer connection;
    private ISharerGUI tgui;
    private int timeout;
    private BlockWriteThread writeThread;
    private FileMgr tempFile;
    private int totalN;
    private Set<Integer> remainedBlocksIdx;
    private IndexElement[] sources;
    private BufferedWriter bufferedWriter;
    private Socket socket;
    private BufferedReader bufferedReader;
    // to iterate blocks index
    private ArrayList<Integer> neededIndex_Array;
    // to Store Connections
    private int MAX_RETRY_TIME = 5;
    private int MAX_BLOCKS_PER_CONNECTION = 5; // set how many blocks can be downloaded in a single connection.
    private int curPeerCount = 0;


    private int cur_BlockArrayIndex = 0; // Index of NeededBlockIndex_Array, Not index of Block!
    private LinkedBlockingDeque<BlockReply> writeQueue = new LinkedBlockingDeque<>();
    /**
     * Create a Peer Download Thread, the thread must be explicitly started.
     * Also it process the incoming request in socket.
     * The thread establish connection with index server to get resources, then connect to peers to download,
     * put blocks in another writing thread.
     * @param tgui an object that implements the terminal logger interface
     * @throws IOException
     */
    public PeerDownloadThread(String relativePathname, SearchRecord searchRecord, ConnectServer connection, ISharerGUI tgui, int timeout) throws IOException {
        this.relativePathname = relativePathname;
        this.searchRecord = searchRecord;
        this.connection = connection;
        this.timeout = timeout;
        this.tgui = tgui;
    }

    @Override
    public void run() {
        tgui.logInfo("Downloading thread running");
        // ask for every peer to send their blocks, if all file success, success and shutdown this thread.
        if (downloadFileFromPeers(relativePathname, searchRecord, connection)){
            tgui.logInfo("Downloading thread completed.");
            try {this.tempFile.closeFile();} catch (Exception ignore){}
        }
        // if download failed, return and print error message
        else {
            tgui.logWarn("Can not download file: " + relativePathname + " not enough resources out there.");
            tgui.logInfo("Downloading thread completed.");
            try {this.tempFile.closeFile();} catch (Exception ignore){}
        }
    }

    private boolean downloadFileFromPeers(String relativePathname, SearchRecord searchRecord, ConnectServer connection) {

        // Pre tasks to do
        // 1. query idx server.
        // 2. create local file.
        // 3. initialise write thread.
        if (!ExecutePreTasks(relativePathname, searchRecord, connection)) return false;

        /**
        Strategy:
            Step 0: Set maximum retry times if failure occurs.
            Step 1: From online resources, build concurrent connections to `maxPeer` amount of peers.
                  ( Step 2: Send a sginle block request to EVERY connection.
            Repeat{ Step 3: receive block replies from EVERY connection.
                  ( Step 4: put block data into @WriteThread to write in the background
                   ( Step 5: Repeat Step 2,3,4,5 until reach maximum retry times.
            Step 6: Check local file integrity.

        Failure Recover:
            If a peer dropped connection or timeout:
                - Continue operation with current reduced number of connections.
                - Establish new connections to next available resources AFTER Step 5.
            If no available resources:
                - Continue operation with current reduced number of connections.
                - Query index server again AFTER Step 6.
                - until it reaches maximum retry times.

        NOTE: to deal with memory issue, `maxPeer` has been set to 1 and their corresponding ArrayList has been removed.
        **/

        // 0. get remained blocks required
        try {
            remainedBlocksIdx = new HashSet<Integer>();
            // update block index needed hashset and check if local file is complete.
            if (UpdateIndexAndCheckComplete()) return true;
            int remainBlocksNum = remainedBlocksIdx.size();
            tgui.logInfo("Total Number of Blocks To Download: " + remainBlocksNum);
        } catch (IOException | BlockUnavailableException e) {
            tgui.logWarn("Blocks are not available. Terminating...");
            return false;
        }


        int retry_times = 0; // current number of retry for the whole process
        int index_sources = 0;
        neededIndex_Array = new ArrayList<>(remainedBlocksIdx);
        Collections.shuffle(neededIndex_Array);

        while (retry_times < MAX_RETRY_TIME) {

            // Step 1. Make connection to peers, restricted to max retry times, maxPeer, maximum amount of peers to connect concurrently.
            if (MakeConnectionToPeer(index_sources)) {
                // Step 2. Keep asking current peer to download block file, update index resources.
                index_sources = SendRequests(index_sources);
            }
            // Step 6. if finish all blocks request or aborted due to error,
            //          check if we complete file transfer and update needed blocks index
            try {
                // if finish download
                if (UpdateIndexAndCheckComplete()) {
                    tgui.logInfo("All blocks Downloaded!");
                    // close existing sockets by sending Goodbye message.
                    try {
                        GoodByeToPeer(socket, bufferedReader, bufferedWriter);
                    } catch (Exception e) {
                        tgui.logWarn("Upload Peer timed out before we say goodbye");
                    }
                    this.writeThread.interrupt();
                    return true;
                }
                /*
                Failure Recovery: if still got blocks remaining, retry
                */
                else {
                    if (retry_times >= MAX_RETRY_TIME) {
                        tgui.logError("Failed! Run out of resources. Retried: " + retry_times + " times.");
                        return false;
                    }
                    // try next available resources
                    index_sources += 1;

                    // if run out of current resources, try querying idx server for new resources again
                    if (index_sources >= sources.length) {
                        //reconnect to idx then query for other resources.
                        ConnectServer connectionRetry = new ConnectServer(this.tgui);
                        if (connectionRetry.MakeConnection(searchRecord.idxSrvAddress, searchRecord.idxSrvPort, searchRecord.idxSrvSecret)) {
                            sources = getSourcesFromIdx(relativePathname, searchRecord, connectionRetry);
                            connectionRetry.shutdown();
                            if (sources == null) {tgui.logWarn("No Available Sources, Retry");}
                            if (sources.length == 0) {tgui.logWarn("No Available Sources, Retry");}
                        }
                        // reset all current connection
                        tgui.logInfo("Retry Connection for " + retry_times + " times.");
                        retry_times += 1; // keep track of our retry times
                        index_sources = 0; // reset which resource taken from resources.
                        try {bufferedWriter.close();bufferedReader.close();socket.close();} catch (IOException ignored){} // if there's still connection, close it.
                    }
                }
            } catch (Exception e) {
                tgui.logError("Error when checking if file finished and close socket");
                return false;
            }
        }
        tgui.logError("Reached maximum retry times, cannot download. Tried times: " + retry_times);
        return false;

    }

    /**
     *  Iterate through every blocks needed and send those corresponding requests to server.
     * @param index_sources
     * @return
     */
    private int SendRequests(int index_sources) {
        while (this.cur_BlockArrayIndex < neededIndex_Array.size()) {
            JoinWithWriteThread(); // If Write thread has too many blocks in queue, let current downloading wait.
            try {
                // Step 3. send request
                singleBlockRequest(tempFile, neededIndex_Array.get(cur_BlockArrayIndex), bufferedWriter);
            }
            catch (IOException e) {
                // Cannot send request, move on to next peer.
                tgui.logInfo("Cannot send request, Retry....");
                GoodByeToPeer(socket, bufferedReader, bufferedWriter);
                break;
            }
            try {
                // Step 4. get block reply
                GetBlockReply_AddQueue();
            }
            catch (InvalidMessageException e) {
                // Cannot read block reply
                tgui.logInfo("Invalid Message received, try next block");
                continue;
            }
            catch (SocketTimeoutException e) {
                // Cannot read block reply
                tgui.logInfo("Lost connection on current Peer, Recovery now...");
                break;
            }
            catch (IOException e) {
                // Cannot read block reply
                tgui.logInfo("Cannot read reply, Retry....");
                continue;
            }

            // Step 5. move on to next block
            this.cur_BlockArrayIndex += 1;
            //sleep(200); // to avoid overloading the thread.
            // if reach our limit, break the connection
            if (this.cur_BlockArrayIndex > MAX_BLOCKS_PER_CONNECTION) {
                tgui.logInfo("Reach limit, create new connections.");
                tgui.logInfo("Number of Blocks remaining: " + (neededIndex_Array.size() - cur_BlockArrayIndex));
                index_sources -= 1;
                GoodByeToPeer(socket, bufferedReader, bufferedWriter);
                break;
            }

        }
        return index_sources;
    }



    /**
     * Send BlockRequest to every peer in array,
     * With Hyper Parameter maxPeer indicate how many peers we communicate each time
     *
     * if maxPeer = 4,
     * Send 4 requests to 4 peers.
     * If one of them timeout, or we cannot send request to,
     * Assign current block request to next peer.
     * return true if at least one request has been sent
     *
     * */
    private void singleBlockRequest (FileMgr tempFile, int blockIdx_Need, BufferedWriter bufferedWriter)
            throws SocketTimeoutException, IOException {
        // 1. (HandShake 1): Send Block request
        writeMsg(bufferedWriter, new BlockRequest(relativePathname, tempFile.getFileDescr().getBlockMd5(blockIdx_Need), blockIdx_Need));
    }

    /**
     * Before sending requests, need to check if there is enough resources,
     * create local file etc.
     * @param relativePathname
     * @param searchRecord
     * @param connection
     * @return
     */
    private boolean ExecutePreTasks(String relativePathname, SearchRecord searchRecord, ConnectServer connection) {
        /*  1. Perform Look up to get a list of available resources and create and send request to share with server */
        try {
            //get online available sources.
            sources = getSourcesFromIdx(relativePathname, searchRecord, connection);
            connection.shutdown();  // shutdown connection with Server
            if (sources == null) {tgui.logWarn("No Available Sources");
                return false;
            }
            if (sources.length == 0) {tgui.logWarn("No Available Sources");
                return false;
            }
        } catch (Exception e) {
            tgui.logError("Fail to query idx Server for resources.");
            return false;
        }

        /* 2. Load temp File Create/Open Local unfinished file with FileMgr */
        try {
            tempFile = getLocalTempFile(relativePathname, searchRecord);
            totalN = tempFile.getFileDescr().getNumBlocks();
            tgui.logInfo("Complete File contains blocks quantity: " + totalN);
        } catch (IOException | NoSuchAlgorithmException e) {
            tgui.logError("Cannot create local file. Terminating...");
            return false;
        }

        /* 3. Initialise Thread to Write block*/
        try {
            writeThread = new BlockWriteThread(tempFile, tgui, writeQueue);
            writeThread.start();
        } catch (IllegalThreadStateException e) {
            tgui.logError("Cannot start BlockWrite thread. Terminating...");
            return false;
        }
        return true;
    }

    /**
     * Check the status of Write thread, if it is heavy loaded, pause the downloading process to wait for IO operation
     * finished writing to local.
     */
    private void JoinWithWriteThread() {
        while (writeThread.incomingWriteBlocks.size() > 5){
            System.out.println("Waiting for blocks writing thread to finish");
            tgui.logInfo("Waiting for blocks writing thread to finish");
            try {
                sleep(100);
            }
            catch (InterruptedException e){
                tgui.logError("Sleep been interrupted");
            }
        }
    }


    /**
     * Create Socket to every available peer and store the reader and writer in array for us to use later
     * Return True if it succefully connects,
     * return false if no connections has been made, meaning maybe no peer is available.
     */
    private boolean MakeConnectionToPeer(int index_of_source) {
        // if we have no resources left, don't create new connection, skip while loop
        //else, make new connection to populate the peer downloading field
        IndexElement ie = sources[index_of_source];
        /* Connect to Peer */
        // try to establish connection and handshake with peer server.
        try {
            tgui.logInfo("Connecting to Peer: " + ie.ip + ", timeout = " + 10 + " seconds");
            this.socket = new Socket();
            socket.connect(new InetSocketAddress(ie.ip, ie.port), 10*1000);
            socket.setSoTimeout(10*1000); // 15 seconds read operation timeout.
            //socket.setSoTimeout(this.timeout);
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            // initialise input and outputStream
            this.bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            this.bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            tgui.logInfo("Connected!");
            //System.out.println("Add another connection");
            curPeerCount += 1;
        } catch (Exception e) {
            tgui.logInfo("Can NOT connect to Peer: " + ie.ip + " : " + ie.port);
            return false;
        }
        return true;
    }



    /**
     * Check if all local file finished,
     * update required block index,
     * reset current progress on block index transfer
     * if all finish, close file stream
     */
    private boolean UpdateIndexAndCheckComplete() throws IOException, BlockUnavailableException {
        // Wait for all written process complete all existing IO operations
        while (true) {
            if (writeThread.incomingWriteBlocks.isEmpty()) {
                if (writeThread.getState().equals(State.BLOCKED)) {
                    break;
                }
                if (writeThread.getState().equals(State.WAITING)) {
                    break;
                }
            }
        }

        // reset params / state
        remainedBlocksIdx.clear();
        this.cur_BlockArrayIndex = 0;
        getNeededBlockIdx(tempFile, totalN, remainedBlocksIdx);
        // check if we have all local files, no need to download
        if (remainedBlocksIdx.size() == 0){tgui.logInfo("Local File exists, no need to download");
            tempFile.closeFile();
            return true;
        }
        neededIndex_Array = new ArrayList<>(remainedBlocksIdx);
        Collections.shuffle(neededIndex_Array);
        return false;
    }


    /**
     * Assume the block index is NOT written to local, call getNeededBlockIdx to get
     * needed index first, then use this method to GET one of the block.
     * 1. send a block request with filename first.
     * 2. read block reply from peer server.
     * return True if success, False if block is not written.
     *
     */
    private void GetBlockReply_AddQueue() throws InvalidMessageException, SocketTimeoutException, IOException{
        //Process Buffer Reader for each connection we made (after removing disconnected peer), to download from them.
        // Listen on reply and also Start a thread Write block to local
        try {
            // 1. call singleBlockRequest() first to request file.
            // 2. download block files.
            Message msg = readMsg(bufferedReader);
            if (!(msg.getClass().getName().equals(BlockReply.class.getName()))) {
                tgui.logError("Invalid Message from peer when fetching blockReply");
                throw new InvalidMessageException();
            }
            // add to writer queue
            writeQueue.add((BlockReply) msg);
        }
        catch (JsonSerializationException e) {
            tgui.logError("Fail to read from BlockReply");
            //System.out.println("Fail to read from BlockReply");
        }
    }


    /**
     * modify array to make it a list of needed blocks index.
     * return an array list of needed index
     * @param tempFile
     * @param N
     * @param remainedBlocksIdx
     * @throws IOException
     * @throws BlockUnavailableException
     */
    private void getNeededBlockIdx(FileMgr tempFile, int N, Set<Integer> remainedBlocksIdx) throws IOException, BlockUnavailableException {
        remainedBlocksIdx.clear();
        for(int b = 0; b< N; b++) {
            // Check Local Block exists completed? check if we have file already, no need to download.
            if (tempFile.isBlockAvailable(b)) {
                byte[] localBlockData = tempFile.readBlock(b);
                if (tempFile.checkBlockHash(b, localBlockData)) {
                    //tgui.logInfo();
                    continue; // next block
                }
            }
            // if not downloaded, add to our download list
            remainedBlocksIdx.add(b);
        }
    }

    /**
     * create or load a local temporary file from disk
     * @param relativePathname
     * @param searchRecord
     * @return
     * @throws IOException
     * @throws NoSuchAlgorithmException
     */
    private FileMgr getLocalTempFile(String relativePathname, SearchRecord searchRecord) throws IOException, NoSuchAlgorithmException {
        // create DOWNLOAD directory for download
        (new File("DOWNLOAD/" + new File(relativePathname).getParent())).mkdirs();

        String downloadPath = new File("DOWNLOAD/", relativePathname).getPath();
        tgui.logInfo("Download into : " + downloadPath);
        return new FileMgr(downloadPath, searchRecord.fileDescr);
    }

    /**
     * Return a list of available Peer sources, IndexElement[]
     * @param relativePathname
     * @param searchRecord
     * @param connection
     * @return
     * @throws IOException
     * @throws JsonSerializationException
     */
    private IndexElement[] getSourcesFromIdx(String relativePathname, SearchRecord searchRecord, ConnectServer connection) throws IOException, JsonSerializationException {
        //send request to get a file with same name and same MD5 code as file described in index server.
        Message msgToSend = new LookupRequest(relativePathname, searchRecord.fileDescr.getFileMd5());
        connection.sendRequest(msgToSend);
        // receive reply
        Message msg_back = connection.getMsg();
        // check if reply is a success flag to return false or true.
        if (!checkReply(msg_back)){
            return null;
        }
        LookupReply lookupReply = (LookupReply) msg_back;
        // get an array of available resources
        IndexElement[] sources = lookupReply.hits;
        tgui.logInfo("Get File Sources Success!");
        return sources;
    }

    /**
     *
     *Send a goodBye message to a peer via BufferReader and Writer, close the socket.
     *
    **/
    private void GoodByeToPeer(Socket socket, BufferedReader bufferedReader, BufferedWriter bufferedWriter) {
        //******************* finish Goodbye message *************
        try {
            // Send Finish GoodBye Signal
            writeMsg(bufferedWriter,new Goodbye());
            tgui.logInfo("GoodBye Sent to: " + socket.getInetAddress());
        } catch (Exception e1) {
            tgui.logWarn("Download Peer: Fail to send good bye signal");
        }
        try {
            // try to close the socket
            socket.close();
        } catch (Exception ignored){}
    }

    /*
     * Methods for writing and reading messages.  By Aaron.
     */

    private void writeMsg(BufferedWriter bufferedWriter, Message msg) throws IOException {
        //tgui.logDebug("sending: "+msg.toString());
        bufferedWriter.write(msg.toString());
        bufferedWriter.newLine();
        bufferedWriter.flush();
    }

    private Message readMsg(BufferedReader bufferedReader) throws IOException, JsonSerializationException {
        String jsonStr = bufferedReader.readLine();
        if(jsonStr!=null) {
            Message msg = (Message) MessageFactory.deserialize(jsonStr);
            return msg;
        } else {
            throw new IOException();
        }
    }

    /*
    check the reply from server, if it's error message, return false, print to console.
    Otherwise return true to indicate reply is valid.
     */
    private boolean checkReply(Message msg_back){
        if (msg_back.getClass().getName().equals(ErrorMsg.class.getName())) {
            tgui.logError(((ErrorMsg) msg_back).msg);
            return false;
        }
        return true;
    }

}
