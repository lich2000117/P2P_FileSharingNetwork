package comp90015.idxsrv.peer;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IOThread;
import comp90015.idxsrv.server.IndexElement;
import comp90015.idxsrv.textgui.ISharerGUI;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A basic IOThread class that accepts connections and puts them
 * onto a blocking queue. If the queue is full then the connection
 * is dropped and a warning is logged.
 * @author aaron
 *
 */
public class PeerDownloadThread extends Thread {
    private final String relativePathname;
    private final SearchRecord searchRecord;
    private final ConnectServer connection;
    private ISharerGUI tgui;
    private int timeout;
    /**
     * Create a Peer IOThread, which attempts to the bind to the provided
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
            // ask for every peer to send their blocks, if success, shutdown this thread.
            if (downloadFileFromPeers(relativePathname, searchRecord, connection)){
                return;
            }
            else {
                tgui.logError("Can not download file, not enough resources.");
                return;
            }
        }
        tgui.logInfo("Downloading thread completed.");
    }

    private boolean downloadFileFromPeers(String relativePathname, SearchRecord searchRecord, ConnectServer connection) {
        // Perform Look up to get a list of available resources
        // create and send request to share with server
        try {

            //get online sources.
            IndexElement[] sources = getSourcesFromIdx(relativePathname, searchRecord, connection);
            if (sources == null) {tgui.logWarn("No Available Sources");
                return false;
            }

            /* Load File */
            // A. Load temp File Create/Open Local unfinished file with FileMgr
            FileMgr tempFile = getLocalTempFile(relativePathname, searchRecord);
            int N = tempFile.getFileDescr().getNumBlocks(); //total number of blocks of the file
            tgui.logInfo("Total Number of Blocks to be downloaded: " + N);

            // get remained blocks required
            Set<Integer> remainedBlocksIdx = new HashSet<Integer>();
            remainedBlocksIdx.clear();
            getNeededBlockIdx(tempFile, N, remainedBlocksIdx);
            // check if we still need to download
            if (remainedBlocksIdx.size() == 0){tgui.logInfo("Local File exists, no need to download");
                return true;
            }

            tgui.logDebug("Waiting.....");
            //TimeUnit.SECONDS.sleep(3); // Wait for 5 seconds before connect to pper

            int peerCount = 0;
            // for every available peer, we try to download file from it
            for (IndexElement ie : sources) {
                peerCount += 1;
                /* Connect to Peer */
                // try to establish connection and handshake with peer server.
                Socket socket;

                BufferedWriter bufferedWriter;
                BufferedReader bufferedReader;
                try {
                    socket = new Socket(ie.ip, ie.port);
                    socket.setSoTimeout(10*1000);
                    InputStream inputStream = socket.getInputStream();
                    OutputStream outputStream = socket.getOutputStream();
                    // initialise input and outputStream
                    bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
                    tgui.logInfo("Connecting to Peer: " + ie.ip + " : " + ie.port);

                } catch (Exception e) {
                    e.printStackTrace();
                    tgui.logInfo("Can NOT connect to Peer: " + ie.ip + " : " + ie.port);
                    continue;
                }

                try {
                    // for every needed block, send download request.
                    for (Integer b : remainedBlocksIdx) {
                        // if a block is successfully downloaded, print info
                        // force skip first peer *************************************************************************************************
                        if (singleBlockRequest(ie, tempFile, b, bufferedReader, bufferedWriter)) {
                            tgui.logInfo("successfully downloaded block: " + b + " From Peer: " + ie.ip);
                        }
                        else{
                            tgui.logInfo("Cannot downloaded block: " + b + " From Peer: " + ie.ip);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    tgui.logInfo("Block Cannot be downloaded from " + ie.ip + " : " + ie.port);
                    continue;
                }
                // close connection to current peer
                GoodByeToPeer(socket, bufferedReader, bufferedWriter);
                // after download, get still remained needed blocks index
                remainedBlocksIdx.clear();
                getNeededBlockIdx(tempFile, N, remainedBlocksIdx);
                // if no blocks needed, break loop.
                if (remainedBlocksIdx.size() == 0) {
                    tgui.logInfo("Finish All Downloading! Number of Peer connected: " + peerCount);
                    connection.shutdown();
                    return true;
                }
            }

            // if reach this line, no peer has required block, print info
            tgui.logWarn("Download Failed, No peer has following blocks: " + remainedBlocksIdx.toString());
            connection.shutdown();
            return false;
        }
        catch (Exception e) {
            e.printStackTrace();
            tgui.logError("Download Error Occur!");
            return false;
        }
    }
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


    /**
     * 1. send a block request with filename first.
     * 2. send another request with the sharer secret
     * 3. get msg from peer back indicate success or not.
     * 4. read block reply from peer server.
     * return True if success
     *
     * @param source
     * @param tempFile
     * @param blockIdx_Need
     * @param bufferedReader
     * @param bufferedWriter
     */
    public boolean singleBlockRequest(IndexElement source, FileMgr tempFile, int blockIdx_Need, BufferedReader bufferedReader, BufferedWriter bufferedWriter) throws IOException, JsonSerializationException {
        // 1. (HandShake 1): Send Block request
        writeMsg(bufferedWriter, new BlockRequest(source.filename, tempFile.getFileDescr().getBlockMd5(blockIdx_Need), blockIdx_Need));

        // 2. download block files.
        Message msg = readMsg(bufferedReader);
        if (!(msg.getClass().getName() == BlockReply.class.getName())) { tgui.logError("Invalid Message");
            return false;
        }
        // 3. Check Block Hash, see if the block we want is the same as received using MD5
        BlockReply block_reply = (BlockReply) msg;
        byte[] receivedData = Base64.getDecoder().decode(new String(block_reply.bytes).getBytes("UTF-8"));
        if (!(tempFile.checkBlockHash(blockIdx_Need, receivedData))){
            tgui.logError("Received Block is not the one we want");
            return false;
        }

        // 6. Write to Local File's block with FileMgr
        if (tempFile.writeBlock(blockIdx_Need, receivedData)){
            tgui.logInfo("Received Block written to File!");
        }
        else{
            tgui.logError("Received Block Not written to File!");
            return false;
        }
        return true;
    }
}
