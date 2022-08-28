package comp90015.idxsrv.peer;


import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;

import java.util.concurrent.LinkedBlockingDeque;

import java.nio.charset.StandardCharsets;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
import comp90015.idxsrv.filemgr.FileDescr;
import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IOThread;
import comp90015.idxsrv.server.IndexElement;
import comp90015.idxsrv.server.IndexMgr;
import comp90015.idxsrv.server.IndexMgr.RETCODE;
import comp90015.idxsrv.textgui.ISharerGUI;
import comp90015.idxsrv.textgui.ITerminalLogger;


/**
 * Server protocol implementation for the Peer upload node, which extends thread
 * and processes an unbounded number of incoming connections until it is interrupted.
 * @author Chenghao Li, References: by Aaron
 *
 */
public class PeerUpload extends Thread {

    /*
     * Some private variables.
     */

    private LinkedBlockingDeque<Socket> incomingConnections;

    private IOThread ioThread;

    private String welcome;

    private String secret;

    FileMgr fileMgr;

    private ISharerGUI logger;
    /**
     * The Client Upload thread get started after sharing a file. The
     * Client starts an independent IOThread to accept connections.
     * @param port
     * @param welcome
     * @param secret
     * @param socketTimeout
     * @param logger
     * @throws IOException
     */
    public PeerUpload(int port,
                  String welcome,
                  String secret,
                  int socketTimeout,
                  ISharerGUI logger,
                  FileMgr fileMgr) throws IOException {
        this.welcome=welcome;
        this.secret=secret;
        this.logger=logger;
        this.fileMgr=fileMgr;
        incomingConnections=new LinkedBlockingDeque<Socket>();
        ioThread = new IOThread(port,incomingConnections,socketTimeout,logger);
        ioThread.start();
    }

    @Override
    public void run() {
        logger.logInfo("Client Uploading thread running.");
        while(!isInterrupted()) {
            try {
                Socket socket = incomingConnections.take();
                processRequest(socket);
                socket.close();
            } catch (InterruptedException e) {
                logger.logWarn("Client Upload interrupted.");
                break;
            } catch (IOException e) {
                logger.logWarn("Client Upload io exception on socket.");
            }
        }
        logger.logInfo("Client Upload waiting for IO thread to stop...");
        ioThread.interrupt();
        try {
            ioThread.join();
        } catch (InterruptedException e) {
            logger.logWarn("Interrupted while joining with IO thread.");
        }
        logger.logInfo("Client Upload thread completed.");
    }


    /**
     * This method is essentially the "Session Layer" logic, where the session is
     * short since it consists of exactly one request on the socket, then the socket
     * is closed.
     * @param socket
     * @throws IOException
     */
    private void processRequest(Socket socket) throws IOException {
        String ip=socket.getInetAddress().getHostAddress();
        int port=socket.getPort();
        logger.logInfo("Client Upload processing request on connection "+ip);
        InputStream inputStream = socket.getInputStream();
        OutputStream outputStream = socket.getOutputStream();
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

        /*
         * Follow the synchronous handshake protocol.
         */

        // write the welcome
        writeMsg(bufferedWriter,new WelcomeMsg(welcome));

        // get a message
        Message msg;
        try {
            msg = readMsg(bufferedReader);
        } catch (JsonSerializationException e1) {
            writeMsg(bufferedWriter,new ErrorMsg("Invalid message"));
            return;
        }

        // check it is an authenticate request
        if(msg.getClass().getName()==AuthenticateRequest.class.getName()) {
            AuthenticateRequest ar = (AuthenticateRequest) msg;
            if(!ar.secret.equals(this.secret)) {
                writeMsg(bufferedWriter,new AuthenticateReply(false));
                return;
            } else {
                writeMsg(bufferedWriter,new AuthenticateReply(true));
            }
        } else {
            writeMsg(bufferedWriter,new ErrorMsg("Expecting AuthenticateRequest"));
            return;
        }

        /*
         * Now get the request and process it. This is a single-request-per-connection
         * protocol.
         */

        // get the request message
        try {
            msg = readMsg(bufferedReader);
        } catch (JsonSerializationException e) {
            writeMsg(bufferedWriter,new ErrorMsg("Invalid message"));
            return;
        }

        // process the request message
        String msgname = msg.getClass().getName();
        if(msgname==BlockRequest.class.getName()) {
            processDownloadRequest(bufferedWriter,(BlockRequest) msg,ip,port);
        } else {
            writeMsg(bufferedWriter,new ErrorMsg("Expecting a file download request message"));
        }

        // close the streams
        bufferedReader.close();
        bufferedWriter.close();
    }

    /*
     * Methods to process each of the possible requests.
     */
    private void processDownloadRequest(BufferedWriter bufferedWriter,BlockRequest msg, String ip, int port) throws IOException {
        // check if requested block is the same as sharing block.
        if (!(fileMgr.getFileDescr().getBlockMd5(msg.blockIdx) == msg.fileMd5)) {
            writeMsg(bufferedWriter,new ErrorMsg("File Block Unmatched!"));
        }
        // else, access local block file and send blockreply
        if (fileMgr.isBlockAvailable(msg.blockIdx)) {
            try {
                byte[] data = fileMgr.readBlock(msg.blockIdx);
                writeMsg(bufferedWriter,new BlockReply(msg.filename, fileMgr.getFileDescr().getFileMd5(), msg.blockIdx, FileDescr.bytesToHex(data)));
            }
            catch (BlockUnavailableException e) {
                writeMsg(bufferedWriter,new ErrorMsg("Block is not available!"));
            }
        }
    }

    /*
     * Methods for writing and reading messages.
     */

    private void writeMsg(BufferedWriter bufferedWriter,Message msg) throws IOException {
        logger.logDebug("sending: "+msg.toString());
        bufferedWriter.write(msg.toString());
        bufferedWriter.newLine();
        bufferedWriter.flush();
    }

    private Message readMsg(BufferedReader bufferedReader) throws IOException, JsonSerializationException {
        String jsonStr = bufferedReader.readLine();
        if(jsonStr!=null) {
            Message msg = (Message) MessageFactory.deserialize(jsonStr);
            logger.logDebug("received: "+msg.toString());
            return msg;
        } else {
            throw new IOException();
        }
    }
}
