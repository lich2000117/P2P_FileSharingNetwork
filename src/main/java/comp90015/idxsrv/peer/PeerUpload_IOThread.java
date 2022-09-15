package comp90015.idxsrv.peer;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingDeque;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IOThread;
import comp90015.idxsrv.textgui.ISharerGUI;

/**
 * An upload thread that runs with peer class.
 * Share files if it is in the share list.
 * Take incoming connections from queue produced by IO thread.
 * Terminate current peer connection if invalid protocol, Algorithm error.
 *
 * ** It creates several sub-sharing threads to enable concurrent multi file sharing function.
 *
 * @author Chenghao Li
 */
public class PeerUpload_IOThread extends Thread {
    private final Peer peer;
    private LinkedBlockingDeque<Socket> incomingConnections;
    private ISharerGUI tgui;
    private IOThread ioThread;
    /**
     * Create a Peer IOThread, which attempts to the bind to the provided
     * port with a server socket. The thread must be explicitly started.
     * Also it process the incoming request in socket
     * @param incomingConnections the blocking queue to put incoming connections
     * @param logger an object that implements the terminal logger interface
     */
    public PeerUpload_IOThread(Peer peer, LinkedBlockingDeque<Socket> incomingConnections,
                               ISharerGUI logger,
                               IOThread ioThread) {
        this.peer = peer;
        this.tgui = logger;
        this.incomingConnections=incomingConnections;
        this.ioThread = ioThread;
    }

    @Override
    public void run() {
        tgui.logInfo("Peer Upload IO thread running");
        while(!isInterrupted()) {
            try {
                Socket socket = incomingConnections.take();
                //socket.setSoTimeout(timeout);
                socket.setSoTimeout(10*1000);
                // Create a sub-thread for this particular socket.
                PeerUploadSubThread subThread = new PeerUploadSubThread(peer, socket, tgui);
                subThread.start();
            } catch (InterruptedException e) {
                tgui.logWarn("Peer Upload thread interrupted.");
                break;
            } catch (IOException e) {
                tgui.logWarn("Error when uploading blocks, skip uploading, keep listening.");
            } catch (Exception e) {
                tgui.logError(e.toString());
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
}
