package comp90015.idxsrv.peer;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IndexElement;
import comp90015.idxsrv.textgui.ISharerGUI;

import java.io.*;
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
    private int MAX_RETRY_TIME = 3;


    private int cur_BlockArrayIndex = 0; // Index of NeededBlockIndex_Array, Not index of Block!
    private int peerTriedCount = 0; // Number of connections made
    private int curPeerCount = 0; // Number of connections on going
    private LinkedBlockingDeque<BlockReply> writeQueue = new LinkedBlockingDeque<>();
    /**
     * Create a Peer Download Thread, which attempts to the bind to the provided
     * port with a server socket. The thread must be explicitly started.
     * Also it process the incoming request in socket
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
        while(!isInterrupted()) {
            // ask for every peer to send their blocks, if all file success, success and shutdown this thread.
            if (downloadFileFromPeers(relativePathname, searchRecord, connection)){
                tgui.logInfo("Successfully download ALL blocks!");
                return;
            }
            // if download failed, return and print error message
            else {
                tgui.logWarn("Can not download file, not enough resources out there.");
                return;
            }
        }
        tgui.logInfo("Downloading thread completed.");
    }

    private boolean downloadFileFromPeers(String relativePathname, SearchRecord searchRecord, ConnectServer connection) {
        // Perform Look up to get a list of available resources
        // create and send request to share with server

        try {

            //get online available sources.
            sources = getSourcesFromIdx(relativePathname, searchRecord, connection);
            connection.shutdown();  // shutdown connection with Server
            if (sources == null) {
                tgui.logWarn("No Available Sources");
                return false;
            }

            /* Load File */
            // A. Load temp File Create/Open Local unfinished file with FileMgr
            tempFile = getLocalTempFile(relativePathname, searchRecord);
            totalN = tempFile.getFileDescr().getNumBlocks();
            tgui.logInfo("Total Number of Blocks for the File: " + totalN);

            // Thread to Write block
            writeThread = new BlockWriteThread(tempFile, tgui, writeQueue);
            writeThread.start();

            // get remained blocks required
            remainedBlocksIdx = new HashSet<Integer>();
            if (UpdateIndexAndCheckComplete()) return true;
            int remainNum = remainedBlocksIdx.size();

            tgui.logInfo("Total Number of Blocks To Download: " + remainNum);
        } catch (Exception e) {
            e.printStackTrace();
            tgui.logError("Download Error Occur! Before connecting to peers");
            return false;
        }
        /* Strategy:
        Phase 1: Send request out in the wilds

           for every available peer, we try to send a single block request to it,
           after we finish all request, then Download

        Phase 2: Download from stored reply
            Use stored Reader to read messages from other peers
            and Write to local file

        Failure Recover:

        */


        // 1. Make connection to peers, restricted to maxPeer, maximum amount of peers to connect.
        //while (MakeConnectionToPeers(neededIndex_Array.size(), sources, socket_Array, bufferReader_Array, bufferWriter_Array)) {
        int retry_times = 0; // number of retry for the whole process
        int index_sources = 0;
        neededIndex_Array = new ArrayList<>(remainedBlocksIdx);
        while (retry_times < MAX_RETRY_TIME) {
            if (MakeConnectionToPeer(index_sources)) {
                //System.out.println(cur_BlockArrayIndex);
                // keep asking current peer to download block file
                while (this.cur_BlockArrayIndex < neededIndex_Array.size()) {
                    // wait for writing queue before we put new one to avoid memory error.
                    WaitForWriteThread();
                    try {
                        singleBlockRequest(tempFile, neededIndex_Array.get(cur_BlockArrayIndex), bufferedWriter);
                        GetBlockReply_AddQueue(); // get reply
                        this.cur_BlockArrayIndex += 1; // move on to next block
                        sleep(200);
                    } catch (Exception e) {
                        // if other exception happens, abort following block requests, try other peers
                        tgui.logError(e.getMessage());
                        break;
                    }
                }
                // if finish all blocks request or aborted due to error, check if we complete file transfer and update needed blocks index
                try {
                    // if finish download
                    if (UpdateIndexAndCheckComplete()) {
                        tgui.logInfo("All blocks Downloaded!");
                        // close existing sockets
                        try {
                            GoodByeToPeer(socket, bufferedReader, bufferedWriter);
                        } catch (Exception e) {
                            tgui.logWarn("Upload Peer timed out before we say goodbye");
                        }
                        this.writeThread.interrupt();
                        return true;
                    } else {
                        // if still got blocks remaining

                        if (retry_times >= MAX_RETRY_TIME) {
                            tgui.logError("Run out of resources, cannot download. Retried: " + retry_times + " times.");
                            return false;
                        }

                        // try next available resources
                        index_sources += 1;

                        // if run out of current resources, try querying idx server for new resources again
                        if (index_sources >= sources.length) {
                            retry_times += 1; // add our retry times
                            index_sources = 0; // reset resources index
                            try {
                                socket.close();
                            }
                            catch (IOException e){}
                            //reconnect to idx then query for other resources.
                            ConnectServer connectionRetry = new ConnectServer(this.tgui);
                            if (connectionRetry.MakeConnection(searchRecord.idxSrvAddress, searchRecord.idxSrvPort, searchRecord.idxSrvSecret)) {
                                sources = getSourcesFromIdx(relativePathname, searchRecord, connectionRetry);
                                connectionRetry.shutdown();
                                if (sources == null) {
                                    tgui.logWarn("No Available Sources");
                                    // continue!!!!
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println(e);
                    tgui.logError("Error when checking if file finished and close socket");
                    return false;
                }
            }
        }
        tgui.logError("Reached maximum retry times, cannot download.");
        return false;

    }

    private void WaitForWriteThread() {
        while (writeThread.incomingWriteBlocks.size() > 5){
            tgui.logInfo("Waiting for blocks writing thread to finish");
            try {
                sleep(800);
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
            tgui.logInfo("Trying to Establish connection to Peer: " + ie.ip);
            socket = new Socket(ie.ip, ie.port);
            socket.setSoTimeout(3*1000);
            //socket.setSoTimeout(this.timeout);
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            // initialise input and outputStream
            this.bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            this.bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            tgui.logInfo("Connected to Peer: " + ie.ip + " : " + ie.port);
            //System.out.println("Add another connection");
            curPeerCount += 1;
        } catch (Exception e) {
            tgui.logInfo("Can NOT connect to Peer: " + ie.ip + " : " + ie.port);
            return false;
        }
        return true;
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
    private void singleBlockRequest (FileMgr tempFile, int blockIdx_Need, BufferedWriter bufferedWriter) throws IOException {
        // 1. (HandShake 1): Send Block request
        writeMsg(bufferedWriter, new BlockRequest(relativePathname, tempFile.getFileDescr().getBlockMd5(blockIdx_Need), blockIdx_Need));
    }


    /**
     * Check if all local file finished,
     * update required block index,
     * reset current progress on block index transfer
     * if all finish, close file stream
     * @return
     * @throws IOException
     * @throws BlockUnavailableException
     */
    private boolean UpdateIndexAndCheckComplete() throws IOException, BlockUnavailableException {
        // Wait for all written process complete
//        try {sleep(5*1000);}
//        catch (Exception e) {}
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
        // until if we finish writing number of blocks we ask to write
        // reset params

        remainedBlocksIdx.clear();
        cur_BlockArrayIndex = 0;
        getNeededBlockIdx(tempFile, totalN, remainedBlocksIdx);
        // check if we have all local files, no need to download
        if (remainedBlocksIdx.size() == 0){tgui.logInfo("Local File exists, no need to download");
            tempFile.closeFile();
            return true;
        }
        neededIndex_Array = new ArrayList<>(remainedBlocksIdx);
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
    private void GetBlockReply_AddQueue() throws IOException{
        //Process Buffer Reader for each connection we made (after removing disconnected peer), to download from them.
        // Listen on reply and also Start a thread Write block to local
        try {
            // 1. call singleBlockRequest() first to request file.
            // 2. download block files.
            Message msg = readMsg(bufferedReader);
            if (!(msg.getClass().getName() == BlockReply.class.getName())) {
                tgui.logError("Invalid Message from peer when fetching blockReply");
                throw new IOException();
            }
            // add to writer queue
            writeQueue.add((BlockReply) msg);
        }
        catch (JsonSerializationException e) {
            tgui.logError("Fail to read from BlockReply");
            //System.out.println("Fail to read from BlockReply");
            return;
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
        FileMgr localTempFile = new FileMgr(downloadPath, searchRecord.fileDescr);
        return localTempFile;
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
    private void GoodByeToPeer(Socket socket, BufferedReader bufferedReader, BufferedWriter bufferedWriter) throws IOException {
        //******************* finish Goodbye message *************
        try {
            // Send Finish GoodBye Signal
            writeMsg(bufferedWriter,new Goodbye());
            // close the socket
            socket.close();
        } catch (Exception e1) {
            writeMsg(bufferedWriter, new ErrorMsg("Download Peer: Fail to exchange good bye signal"));
        }
        tgui.logInfo("GoodBye Exchanged With: " + socket.getInetAddress());
    }

    /*
     * Methods for writing and reading messages.  By Aaron.
     */

    private void writeMsg(BufferedWriter bufferedWriter, Message msg) throws IOException {
        tgui.logDebug("sending: "+msg.toString());
        bufferedWriter.write(msg.toString());
        bufferedWriter.newLine();
        bufferedWriter.flush();
    }

    private Message readMsg(BufferedReader bufferedReader) throws IOException, JsonSerializationException {
        String jsonStr = bufferedReader.readLine();
        if(jsonStr!=null) {
            Message msg = (Message) MessageFactory.deserialize(jsonStr);
            tgui.logDebug("received: "+msg.toString());
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
        if (msg_back.getClass().getName() == ErrorMsg.class.getName()) {
            tgui.logError(((ErrorMsg) msg_back).msg);
            return false;
        }
        return true;
    }

}
