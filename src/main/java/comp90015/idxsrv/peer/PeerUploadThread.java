package comp90015.idxsrv.peer;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingDeque;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IOThread;
import comp90015.idxsrv.textgui.ISharerGUI;

/**
 * A basic IOThread class that accepts connections and puts them
 * onto a blocking queue. If the queue is full then the connection
 * is dropped and a warning is logged.
 * @author aaron
 *
 */
public class PeerUploadThread extends Thread {
    private ServerSocket serverSocket;
    private LinkedBlockingDeque<Socket> incomingConnections;
    private ISharerGUI tgui;
    private int timeout;
    private HashMap<String, ShareRecord> sharingFiles;
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
    public PeerUploadThread(int port,
                            LinkedBlockingDeque<Socket> incomingConnections,
                            int timeout,
                            ISharerGUI logger,
                            IOThread ioThread) throws IOException {
        this.timeout = timeout;
        this.tgui = logger;
        this.incomingConnections=incomingConnections;
        this.ioThread = ioThread;
    }

    @Override
    public void run() {
        tgui.logInfo("Peer IO thread running");
        while(!isInterrupted()) {
            try {
                Socket socket = incomingConnections.take();
                //socket.setSoTimeout(2*1000);
                socket.setSoTimeout(3*1000);
                SendBlockReply(socket);
            } catch (InterruptedException e) {
                tgui.logWarn("Peer Upload thread interrupted.");
                break;
            } catch (IOException e) {
                tgui.logWarn("Error when uploading blocks, skip uploading, keep listening.");
            } catch (Exception e) {
                tgui.logWarn("Socket has been closed!");
            }
        }
        tgui.logInfo("Peer Upload thread thread waiting for IO thread to stop...");
        ioThread.interrupt();
        try {
            ioThread.join();
        } catch (InterruptedException e) {
            tgui.logWarn("Interrupted while joining with IO thread.");
        }
        tgui.logInfo("Peer Upload thread thread completed.");
    }





    private void SendBlockReply(Socket socket) throws IOException {
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
        catch (SocketTimeoutException e){
            tgui.logWarn("Upload Peer Socket Timeout");
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
                SendBlockReply(bufferedWriter, blockRequest, ip, port);
            } catch (IOException ioE) {
                tgui.logWarn("Couldn't send block reply with others");
                return;
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
            catch (SocketTimeoutException e){
                tgui.logWarn("Upload Peer Socket Timeout");
                return;
            }
        }

        // at this stage, the message type is not block request, try say good bye
        if (!(msg.getClass().getName() == Goodbye.class.getName())){
            writeMsg(bufferedWriter,new ErrorMsg("Invalid Message!"));
            return;
        }
        tgui.logInfo("Upload Peer: Goobye Received!");

		//3 ******************* finish Goodbye message *************
        tgui.logInfo("Peer finished all uploadings.");
    }

    private void SendGoodBye(BufferedReader bufferedReader, BufferedWriter bufferedWriter) throws IOException {
        try {
            // Send Finish GoodBye Signal
            // only receive Goodbye from socket side since the socket might be closed if we try to receive something
            writeMsg(bufferedWriter,new Goodbye());
        } catch (Exception e1) {
            writeMsg(bufferedWriter, new ErrorMsg("Upload Peer: Fail to exchange good bye signal"));
        }
    }

    /*
     * Methods to process each of the possible requests.
     */
    private void SendBlockReply(BufferedWriter bufferedWriter, BlockRequest msg, String ip, int port) throws IOException, NoSuchAlgorithmException {

        FileMgr fileMgr = new FileMgr(msg.filename);

		// check if sharing file the same as requested file using MD5.
		if (!(fileMgr.getFileDescr().getBlockMd5(msg.blockIdx).equals(msg.fileMd5))) {
			writeMsg(bufferedWriter,new ErrorMsg(msg.filename + " ] " +fileMgr.getFileDescr().getBlockMd5(msg.blockIdx) + "  Versus:  " + msg.fileMd5 + " **File Block ready unmatch what it supposed to send! It should be the same.**"));
            fileMgr.closeFile();
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
        fileMgr.closeFile();
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

}
