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
    private int maxPeer = 2; // download from how many peers maximum
    private int timeout;

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
    public PeerDownloadThread(String relativePathname, SearchRecord searchRecord, ConnectServer connection, ISharerGUI tgui) throws IOException {
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
        FileMgr tempFile;
        int totalN;
        Set<Integer> remainedBlocksIdx;
        IndexElement[] sources;
        BlockWriteThread writeThread;
        try {

            //get online available sources.
            sources = getSourcesFromIdx(relativePathname, searchRecord, connection);
            if (sources == null) {
                tgui.logWarn("No Available Sources");
                return false;
            }

            /* Load File */
            // A. Load temp File Create/Open Local unfinished file with FileMgr
            tempFile = getLocalTempFile(relativePathname, searchRecord);
            totalN = tempFile.getFileDescr().getNumBlocks();
            tgui.logInfo("Total Number of Blocks of Complete File: " + totalN);

            // Thread to Write block
            writeThread = new BlockWriteThread(tempFile, tgui, writeQueue);
            writeThread.start();

            // get remained blocks required
            remainedBlocksIdx = new HashSet<Integer>();
            if (UpdateIndexAndCheckComplete(writeThread, tempFile, totalN, remainedBlocksIdx)) return true;
            int remainNum = remainedBlocksIdx.size();
            tgui.logInfo("Total Number of Blocks To Download: " + remainNum);
        } catch (Exception e) {
            e.printStackTrace();
            tgui.logError("Download Error Occur! Before connections");
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

        // to iterate blocks index
        ArrayList<Integer> neededIndex_Array = new ArrayList<>(remainedBlocksIdx);
        // to Store Connections
        ArrayList<Socket> socket_Array = new ArrayList<Socket>();
        ArrayList<BufferedReader> bufferReader_Array = new ArrayList<BufferedReader>();
        ArrayList<BufferedWriter> bufferWriter_Array = new ArrayList<BufferedWriter>();

        // 1. Make connection to peers, restricted to maxPeer, maximum amount of peers to connect.
        //while (MakeConnectionToPeers(neededIndex_Array.size(), sources, socket_Array, bufferReader_Array, bufferWriter_Array)) {
        while (MakeConnectionToPeers(neededIndex_Array.size(), sources, socket_Array, bufferReader_Array, bufferWriter_Array)) {
            System.out.println(cur_BlockArrayIndex);
            neededIndex_Array = new ArrayList<>(remainedBlocksIdx);
            if (SendRequestsToConnections(tempFile, neededIndex_Array, socket_Array, bufferReader_Array, bufferWriter_Array)) {
                GetBlockReply_AddQueue(socket_Array, bufferReader_Array, bufferWriter_Array);
            }
            try {
                // if complete file transfer
                if (UpdateIndexAndCheckComplete(writeThread, tempFile, totalN, remainedBlocksIdx)) {
                    System.out.println("Local File Complete!");
                    // close existing sockets
                    for (Socket skt : socket_Array) {
                        int i = socket_Array.indexOf(skt);
                        try {
                            GoodByeToPeer(skt, bufferReader_Array.get(i), bufferWriter_Array.get(i));
                            skt.close();
                        }
                        catch (Exception e) {tgui.logWarn("Upload Peer timed out before we say goodbye");}
                    }
                    return true;
                }
                else{
                    // if still got download remaining, reset count and continue to establish connections
                    //peerTriedCount = 0;
                }
            } catch (Exception e) {
                System.out.println(e);
                tgui.logError("Error when checking if file finished and close socket");
                return false;
            }

        }
        tgui.logError("Run out of resources, cannot download.");
        // close existing sockets

        return false;

    }


    /**
     * Create Socket to every available peer and store the reader and writer in array for us to use later
     * Return True if it succefully connects,
     * return false if no connections has been made, meaning maybe no peer is available.
     */
    private boolean MakeConnectionToPeers(int NumBlocks, IndexElement[] sources, ArrayList<Socket> socketList,
                                       ArrayList<BufferedReader> readerList, ArrayList<BufferedWriter> writerList) {
        int limit = Integer.min(NumBlocks, maxPeer);
        Iterator<IndexElement> itr = Arrays.stream(sources).iterator();
        // if we have no resources left, don't create new connection, skip while loop
        while (peerTriedCount < sources.length) {
            // check if we have already reach maximum peer connections ongoing, we still have resources, return true
            if (curPeerCount == limit) {
                System.out.println("Connection Still on max load.");
                return true;
            }
            //else, make new connection to populate the field
            IndexElement ie = sources[peerTriedCount];
            /* Connect to Peer */
            // try to establish connection and handshake with peer server.
            Socket socket;
            BufferedWriter bufferedWriter;
            BufferedReader bufferedReader;
            try {
                socket = new Socket(ie.ip, ie.port);
                socket.setSoTimeout(20*1000);
                //socket.setSoTimeout(this.timeout);
                InputStream inputStream = socket.getInputStream();
                OutputStream outputStream = socket.getOutputStream();
                // initialise input and outputStream
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
                tgui.logInfo("Connected to Peer: " + ie.ip + " : " + ie.port);
                addConnectionArray(socket, bufferedReader, bufferedWriter, socketList, readerList, writerList);
                System.out.println("Add another connection");
                peerTriedCount += 1;
                curPeerCount += 1;
            } catch (Exception e) {
                tgui.logInfo("Can NOT connect to Peer: " + ie.ip + " : " + ie.port);
                peerTriedCount += 1;
                continue; // move on to next peer
            }

        }
        //if reach this line, No connection has been made, All peers unavailable
        if (curPeerCount == 0) {
            tgui.logWarn("All peers unavailable right now.");
            return false;
        }
        return true;
    }

    /**
     * Add our Socket, Reader and Writer to array
     */
    private void addConnectionArray(Socket socket, BufferedReader bufferedReader, BufferedWriter bufferedWriter,
                  ArrayList<Socket> socketList, ArrayList<BufferedReader> readerList, ArrayList<BufferedWriter> writerList) {
        socketList.add(socket);
        readerList.add(bufferedReader);
        writerList.add(bufferedWriter);
    }
    /**
     * Remove our Socket, Reader and Writer to array
     * Only use when NOT iterating them.
     */
    private void removeConnectionArray(int index,
                                      ArrayList<Socket> socketList, ArrayList<BufferedReader> readerList, ArrayList<BufferedWriter> writerList) {
        socketList.remove(index);
        socketList.remove(index);
        readerList.remove(index);
        writerList.remove(index);
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
    private boolean SendRequestsToConnections(FileMgr tempFile, ArrayList<Integer> neededIndex_Array,
                                           ArrayList<Socket> socket_Array, ArrayList<BufferedReader> reader_Array,
                                           ArrayList<BufferedWriter> writer_Array) {
        // iterate through our writer and write messages to peers.
        Iterator<BufferedWriter> itr = writer_Array.iterator();

        while (itr.hasNext()) {
            BufferedWriter writer = itr.next();
            int connection_index = writer_Array.indexOf(writer);
            // for next ONE needed block, send download request.

            // if no blocks request to be sent, return.
            if (neededIndex_Array.size() == (this.cur_BlockArrayIndex)) {
                return true;
            }

            // Try to send a blockRequest, update index needed.
            try {
                singleBlockRequest(tempFile, neededIndex_Array.get(this.cur_BlockArrayIndex), writer);
                this.cur_BlockArrayIndex += 1;
            }
            catch (SocketTimeoutException e){
                // if time out, remove connection and continue to next peer.
                tgui.logWarn("Cannot send request, Connection to Peer lost.");
                curPeerCount -= 1;s
                itr.remove();
                socket_Array.remove(connection_index);
                reader_Array.remove(connection_index);
            }
            catch (IOException e) {
                // if other error occurs, remove connection and continue to next peer.
                tgui.logWarn("Cannot send request, but something else went wrong, not time out");
                itr.remove();
                curPeerCount -= 1;
                itr.remove();
                socket_Array.remove(connection_index);
                reader_Array.remove(connection_index);
            }

        }
        // If we lose all connection, notify user, reduce block size or increase timeout
        if (curPeerCount <= 0) {
            tgui.logError("All Peer timed out, increase time out limit or decrease block size, or decrease maxPeer");
            System.out.println("All Peer timed out, increase time out limit or decrease block size, or decrease maxPeer");
            return false;
        }
        // No more peer to send request. method return.
        return true;
    }

    private boolean SingleBlockWrite(FileMgr tempFile, BlockReply msg) {
        // 3. Check Block Hash, see if the block we want is the same as received using MD5
        try {
            BlockReply block_reply = msg;
            int blockIdx = block_reply.blockIdx;
            byte[] receivedData = Base64.getDecoder().decode(new String(block_reply.bytes).getBytes("UTF-8"));
            if (!(tempFile.checkBlockHash(blockIdx, receivedData))) {
                tgui.logError("Received Block is not the one we want");
                return false;
            }

            // 6. Write to Local File's block with FileMgr
            if (tempFile.writeBlock(blockIdx, receivedData)) {
                tgui.logInfo("Received Block written to File!");
            } else {
                tgui.logError("Received Block Not written to File!");
                return false;
            }
        }
        catch (IOException e) {
            tgui.logError("IO exception when writing to File!");
            return false;
        }
        return true;
    }

    /**
     * Check if all local file finished,
     * update required block index,
     * reset current progress on block index transfer
     * if all finish, close file stream
     * @param tempFile
     * @param totalN
     * @param remainedBlocksIdx
     * @return
     * @throws IOException
     * @throws BlockUnavailableException
     */
    private boolean UpdateIndexAndCheckComplete(BlockWriteThread blockWriteThread, FileMgr tempFile, int totalN, Set<Integer> remainedBlocksIdx) throws IOException, BlockUnavailableException {
        // Wait for all written process complete
//        try {sleep(3*1000);}
//        catch (Exception e) {}
        while (true) {
            System.out.println("Waiting for block write.");
            if (blockWriteThread.getState().equals(State.BLOCKED)){
                break;
            }
            if (blockWriteThread.getState().equals(State.WAITING)){
                break;
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
        return false;
    }


    private void singleBlockRequest ( FileMgr tempFile, int blockIdx_Need, BufferedWriter bufferedWriter) throws IOException {
        // 1. (HandShake 1): Send Block request
        writeMsg(bufferedWriter, new BlockRequest(relativePathname, tempFile.getFileDescr().getBlockMd5(blockIdx_Need), blockIdx_Need));
    }


    /**
     * Assume the block index is NOT written to local, call getNeededBlockIdx to get
     * needed index first, then use this method to GET one of the block.
     * 1. send a block request with filename first.
     * 2. read block reply from peer server.
     * return True if success, False if block is not written.
     *
     */
    private void GetBlockReply_AddQueue(ArrayList<Socket> socket_Array,
                                        ArrayList<BufferedReader> reader_Array, ArrayList<BufferedWriter> writer_Array) {
        //Process Buffer Reader for each connection we made (after removing disconnected peer), to download from them.
        Iterator<BufferedReader> itr = reader_Array.iterator();
        while (itr.hasNext()) {
            BufferedReader reader = itr.next();
            int connection_index = reader_Array.indexOf(reader);
            // Listen on reply and also Start a thread Write block to local
            try {
                // 1. call singleBlockRequest() first to request file.
                // 2. download block files.
                Message msg = readMsg(reader);
                if (!(msg.getClass().getName() == BlockReply.class.getName())) {
                    tgui.logError("Invalid Message from peer when fetching blockReply");
                    return;
                }
                // add to writer queue
                writeQueue.add((BlockReply) msg);
            } catch (SocketTimeoutException e) {
                tgui.logError("Timeout when trying to receive BlockReply");
                curPeerCount -= 1;
                itr.remove();
                socket_Array.remove(connection_index);
                writer_Array.remove(connection_index);
                return;
            }
            catch (Exception e) {
                tgui.logError("Fail to read from BlockReply");
                System.out.println("Fail to read from BlockReply");
                return;
            }
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
        connection.shutdown();  // shutdown connection with Server
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
            // receive GoodBye Signal
            Goodbye gb = (Goodbye) readMsg(bufferedReader);
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
