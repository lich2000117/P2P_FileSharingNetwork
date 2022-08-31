package comp90015.idxsrv.peer;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IOThread;
import comp90015.idxsrv.server.IndexElement;
import comp90015.idxsrv.textgui.ISharerGUI;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * A basic IOThread class that accepts connections and puts them
 * onto a blocking queue. If the queue is full then the connection
 * is dropped and a warning is logged.
 * @author aaron
 *
 */
public class PeerDownloadThread extends Thread {
    private ServerSocket serverSocket;
    private LinkedBlockingDeque<Socket> incomingConnections;
    private ISharerGUI tgui;
    private int timeout;
    private IOThread ioThread;
    /**
     * Create a Peer IOThread, which attempts to the bind to the provided
     * port with a server socket. The thread must be explicitly started.
     * Also it process the incoming request in socket
     * @param port the port for the server socket
     * @param incomingConnections the blocking queue to put incoming connections
     * @param timeout the timeout value to be set on incoming connections
     * @param logger an object that implements the terminal logger interface
     * @throws IOException
     */
    public PeerDownloadThread(int port,
                              LinkedBlockingDeque<Socket> incomingConnections,
                              int timeout,
                              ISharerGUI logger) throws IOException {
        this.timeout = timeout;
        this.tgui = logger;
        this.incomingConnections=incomingConnections;
    }

    @Override
    public void run() {
        tgui.logInfo("Downloading thread running");
        while(!isInterrupted()) {
            // ask for every peer to send their blocks
        }
        tgui.logInfo("Downloading thread waiting for IO thread to stop...");
        ioThread.interrupt();
        try {
            ioThread.join();
        } catch (InterruptedException e) {
            tgui.logWarn("Interrupted while joining with IO thread.");
        }
        tgui.logInfo("Downloading thread completed.");
    }





    private void ProcessPeerRequest(Socket socket) throws IOException {
        String ip=socket.getInetAddress().getHostAddress();
        int port=socket.getPort();
        tgui.logInfo("Client Upload processing request on connection "+ip);
        InputStream inputStream = socket.getInputStream();
        OutputStream outputStream = socket.getOutputStream();
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

        /*
         * Follow the synchronous handshake protocol.
         */

        // get a first message
        Message msg;
        try {
            msg = readMsg(bufferedReader);
        } catch (JsonSerializationException e1) {
            writeMsg(bufferedWriter,new ErrorMsg("Invalid message"));
            return;
        }
        // 1. Continuously get a message with block info
        while (msg.getClass().getName()==BlockRequest.class.getName()) {
            BlockRequest blockRequest;
            blockRequest = (BlockRequest) msg;

            /* 2
             * Now process our block request. This is a single-request-per-connection
             * protocol.
             */

            try {
                ProcessPeerRequest(bufferedWriter, blockRequest, ip, port);
            } catch (IOException ioE) {
                tgui.logError("Couldn't read local file to share with others");
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                tgui.logError("No such Algorithm");
            }
            /* 2
             * Get Another message
             */
//            bufferedReader.reset();
//            bufferedWriter.flush();
            try {
                msg = readMsg(bufferedReader);
            } catch (JsonSerializationException e1) {
                writeMsg(bufferedWriter,new ErrorMsg("Invalid message"));
                return;
            }
        }

        // at this stage, the message type is not block request, try say good bye
        if (!(msg.getClass().getName() == Goodbye.class.getName())){
            writeMsg(bufferedWriter,new ErrorMsg("Invalid Message!"));
            return;
        }

		//3 ******************* finish Goodbye message *************
        SendGoodBye(bufferedReader, bufferedWriter);

    }

    private void SendGoodBye(BufferedReader bufferedReader, BufferedWriter bufferedWriter) throws IOException {
        try {
            // Send Finish GoodBye Signal
            writeMsg(bufferedWriter,new Goodbye());
            // receive GoodBye Signal
            Goodbye gb = (Goodbye) readMsg(bufferedReader);
        } catch (Exception e1) {
            writeMsg(bufferedWriter, new ErrorMsg("Fail to exchange good bye signal"));
        }
    }

    /*
     * Methods to process each of the possible requests.
     */
    private void ProcessPeerRequest(BufferedWriter bufferedWriter, BlockRequest msg, String ip, int port) throws IOException, NoSuchAlgorithmException {

        FileMgr fileMgr = new FileMgr(msg.filename);

		// check if sharing file the same as requested file using MD5.
		if (!(fileMgr.getFileDescr().getBlockMd5(msg.blockIdx).equals(msg.fileMd5))) {
			writeMsg(bufferedWriter,new ErrorMsg(msg.filename + " ] " +fileMgr.getFileDescr().getBlockMd5(msg.blockIdx) + "  Versus:  " + msg.fileMd5 + " **File Block ready unmatch what it supposed to send! It should be the same.**"));
            return;
		}

		// access local block file and send blockReply
		if (fileMgr.isBlockAvailable(msg.blockIdx)) {
			try {
				byte[] data = fileMgr.readBlock(msg.blockIdx);
				writeMsg(bufferedWriter,new BlockReply(msg.filename, fileMgr.getFileDescr().getFileMd5(), msg.blockIdx, Base64.getEncoder().encodeToString(data)));
			}
			catch (BlockUnavailableException e) {
				writeMsg(bufferedWriter,new ErrorMsg("Block is not available!"));
			}
		}
        tgui.logInfo("Peer Server Send File successfully!");
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
