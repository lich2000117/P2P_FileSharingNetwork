package comp90015.idxsrv.peer;

import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.textgui.ISharerGUI;

import java.io.*;
import java.net.InetAddress;
import java.security.NoSuchAlgorithmException;

/**
 * A Thread that make a connection to idx Server and share the file
 * @author Chenghao Li
 */
public class PeerShareThread extends Thread {
    private final File file;
    private final InetAddress idxAddress;
    private final int idxPort;
    private final String idxSecret;
    private final String shareSecret;
    private ISharerGUI tgui;
    private String basedir;
    private int peerPort;
    private Peer peer;


    /**
     * Create a Peer Download Thread, which attempts to the bind to the provided
     * port with a server socket. The thread must be explicitly started.
     * Also it process the incoming request in socket
     * @param tgui an object that implements the terminal logger interface
     * @throws IOException
     */
    public PeerShareThread(Peer peer, String basedir, File file, InetAddress idxAddress, int idxPort, String idxSecret,
                           String shareSecret, ISharerGUI tgui, int peerPort) {
        this.peer = peer;
        this.basedir = basedir;
        this.file = file;
        this.idxAddress = idxAddress;
        this.idxPort = idxPort;
        this.idxSecret = idxSecret;
        this.shareSecret = shareSecret;
        this.tgui = tgui;
        this.peerPort = peerPort;
    }

    @Override
    public void run() {
        tgui.logInfo("Trying to Share with Idx Server in this Thread...");
        while(!isInterrupted()) {
            // ask for every peer to send their blocks, if all file success, success and shutdown this thread.
            if (shareWithServer(file, idxAddress, idxPort, idxSecret, shareSecret)){
                tgui.logInfo("Successfully share File to Idx Server!");
                tgui.logInfo("Share Request thread completed.");
                return;
            }
            // if download failed, return and print error message
            else {
                tgui.logWarn("Can not share file with Idx Server");
                tgui.logInfo("Share Request thread completed.");
                return;
            }
        }
    }


    /**
     * Share file with Idx Server by
     * 1. Checking file path
     * 2. Connect to Idx Server
     * 3. Send Share Requests
     * 4. Add to GUI
     * @param file
     * @param idxAddress
     * @param idxPort
     * @param idxSecret
     * @param shareSecret
     * @return
     */
    private boolean shareWithServer(File file, InetAddress idxAddress, int idxPort, String idxSecret, String shareSecret) {
        // Check if file in base dir
        if (! file.getParent().contains(basedir)){tgui.logWarn("Sharing File Not in Base Directory!");
            return false;
        }
        FileMgr fileMgr;
        String relativePathName;
        String filePath;
        // FileMgr load local file, Load before sending connection to ensure no timeout.
        try{

            filePath = file.getPath();
            relativePathName = new File(basedir).toURI().relativize(new File(filePath).toURI()).getPath();
            fileMgr = new FileMgr(filePath);
        } catch (NoSuchAlgorithmException e) {
            tgui.logError("No such Algorithm! Cannot use FileMgr to create local file.");
            return false;
        } catch (IOException e) {
            tgui.logError("IO operation failed! Cannot use FileMgr to create local file.");
            return false;
        }
        // try to establish connection and handshake with idx server.
        ConnectServer connection = new ConnectServer(this.tgui);
        if (!connection.MakeConnection(idxAddress, idxPort, idxSecret)) return false;

        // create and send request to share with idx server
        try{
            // send request
            connection.sendRequest(new ShareRequest(fileMgr.getFileDescr(), relativePathName, shareSecret, peerPort));

            // receive reply from idx server
            Message msg_back = connection.getMsg();
            // check if it's error message
            if (!checkReply(msg_back)){tgui.logWarn("Something wrong with Server Side.");
                return false;
            }

            // Add to GUI
            ShareReply reply = (ShareReply) msg_back;
            ShareRecord newRecord = new ShareRecord(fileMgr, reply.numSharers,"Ready", idxAddress,
                    idxPort, idxSecret, shareSecret);
            tgui.addShareRecord(relativePathName, newRecord);
            // Add to sharing file list
            peer.sharingFileNames.add(relativePathName);
            connection.shutdown();
            fileMgr.closeFile();
            return true;
        }
        catch (FileNotFoundException e) {
            tgui.logError("File out of Directory!");
            return false;
        } catch (JsonSerializationException e) {
            tgui.logError("JsonSerializationException");
            return false;
        } catch (IOException e) {
            tgui.logError("IO operation failed! Abort");
            return false;
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
