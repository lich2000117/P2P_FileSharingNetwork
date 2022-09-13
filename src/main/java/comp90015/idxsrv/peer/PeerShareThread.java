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
    private String basedir;
    private int peerPort;
    public boolean success;


    /**
     * Create a Peer Download Thread, which attempts to the bind to the provided
     * port with a server socket. The thread must be explicitly started.
     * Also it process the incoming request in socket
     *
     * @throws IOException
     */
    public PeerShareThread(String basedir, File file, InetAddress idxAddress, int idxPort, String idxSecret,
                           String shareSecret, int peerPort) {
        this.basedir = basedir;
        this.file = file;
        this.idxAddress = idxAddress;
        this.idxPort = idxPort;
        this.idxSecret = idxSecret;
        this.shareSecret = shareSecret;
        this.peerPort = peerPort;
    }

    @Override
    public void run() {
        while(!isInterrupted()) {
            // ask for every peer to send their blocks, if all file success, success and shutdown this thread.
            if (shareWithServer(file, idxAddress, idxPort, idxSecret, shareSecret)){
                this.success=true;
                return;
            }
            // if download failed, return and print error message
            else {
                this.success=false;
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
        if (! file.getParent().contains(basedir)){;
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
            return false;
        } catch (IOException e) {
            return false;
        }
        // try to establish connection and handshake with idx server.
        ConnectServer connection = new ConnectServer();
        if (!connection.MakeConnection(idxAddress, idxPort, idxSecret)) return false;

        // create and send request to share with idx server
        try{
            // send request
            connection.sendRequest(new ShareRequest(fileMgr.getFileDescr(), relativePathName, shareSecret, peerPort));

            // receive reply from idx server
            Message msg_back = connection.getMsg();
            // check if it's error message
            if (!checkReply(msg_back)){
                return false;
            }

            // Add to GUI
            ShareReply reply = (ShareReply) msg_back;
            ShareRecord newRecord = new ShareRecord(fileMgr, reply.numSharers,"Ready", idxAddress,
                    idxPort, idxSecret, shareSecret);
            connection.shutdown();
            fileMgr.closeFile();
            return true;
        }
        catch (FileNotFoundException e) {
            return false;
        } catch (JsonSerializationException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /*
    check the reply from server, if it's error message, return false, print to console.
    Otherwise return true to indicate reply is valid.
     */
    private boolean checkReply(Message msg_back){
        if (msg_back.getClass().getName().equals(ErrorMsg.class.getName())) {
            return false;
        }
        return true;
    }

}
