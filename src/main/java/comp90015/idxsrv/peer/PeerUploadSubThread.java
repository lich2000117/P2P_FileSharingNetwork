package comp90015.idxsrv.peer;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IndexElement;
import comp90015.idxsrv.textgui.ISharerGUI;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * A Thread that make a connection to idx Server and share the file
 * @author Chenghao Li
 */
public class PeerUploadSubThread extends Thread {
    private final Socket socket;
    private ISharerGUI tgui;
    private final Peer peer;

    /**
     * Create a Peer Download Thread, which attempts to the bind to the provided
     * port with a server socket. The thread must be explicitly started.
     * Also it process the incoming request in socket
     * @param tgui an object that implements the terminal logger interface
     * @throws IOException
     */
    public PeerUploadSubThread(Peer peer, Socket socket, ISharerGUI tgui) {
        this.peer = peer        ;
        this.socket = socket;
        this.tgui = tgui;
    }

    @Override
    public void run() {
        tgui.logInfo("New Thread Trying to Upload TO: " + socket.getInetAddress());
        if (StartUpload(socket)){
            tgui.logInfo("Successfully Upload File to Peer: " + socket.getInetAddress());
        }
        // if download failed, return and print error message
        else {
            tgui.logWarn("Cannot Upload File to Peer: " + socket.getInetAddress());
        }
        tgui.logInfo("Upload sub thread completed.");
        return;
    }


    /**
     * Take the incoming connection and process it.
     * @param socket
     * @throws IOException
     */
    private boolean StartUpload(Socket socket){

        // initialise connection set up
        String ip=socket.getInetAddress().getHostAddress();
        int port=socket.getPort();
        tgui.logInfo("Client Upload processing request on connection "+ip);
        try {
            InputStream inputStream = socket.getInputStream();
            OutputStream outputStream = socket.getOutputStream();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

            /*
             * Follow the synchronous handshake protocol.
             */

            // get first block reply message
            Message msg;
            try {
                msg = readMsg(bufferedReader);
            } catch (JsonSerializationException e1) {
                tgui.logWarn("Invalid message, JsonSerialisation...");
                return false;
            } catch (SocketTimeoutException e) {
                tgui.logWarn("Upload Peer Socket Timeout");
                return false;
            }
            catch (IOException e){
                tgui.logError(e.getMessage());
                tgui.logWarn("IO Exception encountered when reading first block message");
                return false;
            }
            // 1. Check if message is a request and Continuously get a message with block info
            BlockRequest blockRequest;
            FileMgr fileMgr = null;
            while (msg.getClass().getName()==BlockRequest.class.getName()) {
                blockRequest = (BlockRequest) msg;
                /* 2
                 * Now process our block request. This is a single-request-per-connection
                 * protocol.
                 */

                try {
                    fileMgr = new FileMgr(blockRequest.filename);
                    if (!ProcessBlockRequests(peer, fileMgr, bufferedWriter, blockRequest)) {
                        tgui.logWarn("Terminate connection with Peer: " + ip);
                        return false;
                    }
                } catch (IOException ioE) {
                    tgui.logWarn("Couldn't send block: " + blockRequest.blockIdx);
                } catch (NoSuchAlgorithmException e) {
                    tgui.logError("No such Algorithm, Terminate connection.");
                    return false;
                } catch (Exception e) {
                    tgui.logWarn("Current Block Upload error, skip to next");
                }
                /* 2
                 * Get Another message
                 */
                try {
                    msg = readMsg(bufferedReader);
                } catch (JsonSerializationException e1) {
                    tgui.logWarn("Invalid message Terminate connection with Peer: " + ip);
                    return false;
                } catch (SocketTimeoutException e) {
                    tgui.logWarn("Upload Peer Socket Timeout");
                    tgui.logWarn("Terminate connection with Peer: " + ip);
                    return false;
                }
                catch (IOException e){
                    tgui.logWarn("IO Exception encountered when reading block message");
                    return false;
                }
            }
            if (fileMgr!=null) fileMgr.closeFile();
            // at this stage, the message type is not block request, it should be Goodbye message
            if (!(msg.getClass().getName() == (Goodbye.class.getName()))) {
                writeMsg(bufferedWriter, new ErrorMsg("Invalid Message!"));
                return false;
            }
        }
        catch (IOException e){
            tgui.logWarn("IO Exception encountered when uploading file");
            return false;
        }
        tgui.logInfo("Upload Peer: Goodbye Received!");
        return true;
    }


    /*
     * Methods to process each of the possible block requests and send block back.
     */
    private boolean ProcessBlockRequests(Peer peer, FileMgr fileMgr, BufferedWriter bufferedWriter, BlockRequest msg)
                        throws NoSuchAlgorithmException, IOException, Exception {

        // Check if the file requested is in our sharing list.
        if (! peer.sharingFileNames.contains(msg.filename)){
            tgui.logWarn("The file requested is no longer for share. File Name: " + msg.filename);
            return false;
        }

        int blockidx = msg.blockIdx;
        // check if sharing file the same as requested file using MD5.
        if (!(fileMgr.getFileDescr().getBlockMd5(blockidx).equals(msg.fileMd5))) {
            writeMsg(bufferedWriter,new ErrorMsg(msg.filename + " ] " +fileMgr.getFileDescr().getBlockMd5(blockidx) + "  Versus:  " + msg.fileMd5 + " **File Block ready unmatch what it supposed to send! It should be the same.**"));
            tgui.logWarn("MD5 check failed for file. File Name: " + msg.filename);
            return false;
        }
        // access local block file and send blockReply
        if (fileMgr.isBlockAvailable(blockidx)) {
            try {
                byte[] data = fileMgr.readBlock(blockidx);
                String c_d = Base64.getEncoder().encodeToString(data);
                BlockReply bp = new BlockReply(msg.filename, fileMgr.getFileDescr().getFileMd5(), blockidx, c_d);
                writeMsg(bufferedWriter, bp);
            }
            catch (BlockUnavailableException e) {
                writeMsg(bufferedWriter,new ErrorMsg("Block is not available!"));
            }
            catch (OutOfMemoryError ignored) {}
        }
        tgui.logInfo("sent Block: " + blockidx);
        return true;
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
            //tgui.logDebug("received: "+msg.toString());
            return msg;
        } else {
            throw new IOException();
        }
    }

}
