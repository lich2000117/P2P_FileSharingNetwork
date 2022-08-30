package comp90015.idxsrv.peer;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingDeque;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
import comp90015.idxsrv.filemgr.FileDescr;
import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.textgui.ISharerGUI;

/**
 * A basic IOThread class that accepts connections and puts them
 * onto a blocking queue. If the queue is full then the connection
 * is dropped and a warning is logged.
 * @author aaron
 *
 */
public class PeerIOThread extends Thread {
    private ServerSocket serverSocket;
    private LinkedBlockingDeque<Socket> incomingConnections;
    private ISharerGUI tgui;
    private int timeout;

    private HashMap<String, ShareRecord> sharingFiles;
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
    public PeerIOThread(int port,
                    LinkedBlockingDeque<Socket> incomingConnections,
                    int timeout,
                    ISharerGUI logger,
                    HashMap<String, ShareRecord> sharingFiles) throws IOException {
        this.timeout = timeout;
        this.tgui = logger;
        this.incomingConnections=incomingConnections;
        this.sharingFiles = sharingFiles;
        serverSocket = new ServerSocket(port);

    }

    /**
     * Shutdown the server socket, which simply closes it.
     * @throws IOException
     */
    public void shutdown() throws IOException {
        serverSocket.close();
    }

    @Override
    public void run() {
        tgui.logInfo("Peer IO thread running");
        while(!isInterrupted()) {
            try {
                Socket socket = serverSocket.accept();
                try {
                    socket.setSoTimeout(this.timeout);
                    if(!incomingConnections.offer(socket)) {
                        socket.close();
                        tgui.logWarn("Peer IO thread dropped connection - incoming connection queue is full.");
                    }
                    else {
                        // process upcoming socket.
                        SendRequestedBlocks(socket);
                        socket.close();
                    }
                } catch (IOException e) {
                    tgui.logWarn("Peer Something went wrong with the connection.");
                }
            } catch (IOException e) {
                tgui.logError("Peer IO thread failed to accept.");
                break;
            }
        }

        tgui.logInfo("Peer Upload Server thread completed.");
    }





    private void SendRequestedBlocks(Socket socket) throws IOException {
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

//		// 1. write the welcome
        writeMsg(bufferedWriter,new WelcomeMsg("welcome to peer sharer!"));


        // 2. get a message with block info
        Message msg;
        try {
            msg = readMsg(bufferedReader);
        } catch (JsonSerializationException e1) {
            writeMsg(bufferedWriter,new ErrorMsg("Invalid message"));
            return;
        }
        BlockRequest blockRequest;
        if(msg.getClass().getName()==BlockRequest.class.getName()) {
            blockRequest = (BlockRequest) msg;
        }
        else {writeMsg(bufferedWriter,new ErrorMsg("Invalid message")); return;}


		// 2.2 check secret by getting another message
		try {
			msg = readMsg(bufferedReader);
		} catch (JsonSerializationException e1) {
			writeMsg(bufferedWriter,new ErrorMsg("Invalid message"));
			return;
		}
		if(msg.getClass().getName()==AuthenticateRequest.class.getName()) {
			AuthenticateRequest ar = (AuthenticateRequest) msg;

            tgui.logError("True" + sharingFiles.get(blockRequest.fileMd5));

			if(!ar.secret.equals(sharingFiles.get(blockRequest.fileMd5).sharerSecret)) {
				writeMsg(bufferedWriter,new AuthenticateReply(false));
				return;
			} else {
				writeMsg(bufferedWriter,new AuthenticateReply(true));
			}
		} else {
			writeMsg(bufferedWriter,new ErrorMsg("Expecting AuthenticateRequest"));
			return;
		}
//
//		/* 3.0
//		 * Now process our block request. This is a single-request-per-connection
//		 * protocol.
//		 */
//
		SendRequestedBlocks(bufferedWriter, blockRequest,ip,port);
//
//		//******************* finish Goodbye message *************
        try {
            // Send Finish GoodBye Signal
            writeMsg(bufferedWriter,new Goodbye());
            // receive GoodBye Signal
            Goodbye gb = (Goodbye) readMsg(bufferedReader);
            // close the streams
            bufferedReader.close();
            bufferedWriter.close();
        } catch (Exception e1) {
            writeMsg(bufferedWriter, new ErrorMsg("Fail to exchange good bye signal"));
        }

    }

    /*
     * Methods to process each of the possible requests.
     */
    private void SendRequestedBlocks(BufferedWriter bufferedWriter, BlockRequest msg, String ip, int port) throws IOException {
        FileMgr fileMgr = sharingFiles.get(msg.fileMd5).fileMgr; // use stored sharing file.

		// check if sharing file the same as requested file using MD5.
		if (!(fileMgr.getFileDescr().getBlockMd5(msg.blockIdx).equals(msg.fileMd5))) {
			writeMsg(bufferedWriter,new ErrorMsg("**File Block ready unmatch what it supposed to send! It should be the same.**"));
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

}
