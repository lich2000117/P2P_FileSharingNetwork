package comp90015.idxsrv.peer;

import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IndexElement;
import comp90015.idxsrv.textgui.ISharerGUI;

import java.io.IOException;
import java.net.InetAddress;

/**
 * A Thread that make a connection to idx Server and share the file
 * @author Chenghao Li
 */
public class PeerSearchThread extends Thread {
    private final InetAddress idxAddress;
    private final int idxPort;
    private final String idxSecret;
    private ISharerGUI tgui;
    private String[] keywords;
    private int maxhits;

    /**
     * Create a Peer Download Thread, which attempts to the bind to the provided
     * port with a server socket. The thread must be explicitly started.
     * Also it process the incoming request in socket
     * @param tgui an object that implements the terminal logger interface
     * @throws IOException
     */
    public PeerSearchThread(String[] keywords,
                            int maxhits,
                            InetAddress idxAddress,
                            int idxPort,
                            String idxSecret, ISharerGUI tgui) {
        this.keywords = keywords;
        this.maxhits = maxhits;
        this.idxAddress = idxAddress;
        this.idxPort = idxPort;
        this.idxSecret = idxSecret;
        this.tgui = tgui;
    }

    @Override
    public void run() {
        tgui.logInfo("Trying to Search with Idx Server in this Thread...");
        while(!isInterrupted()) {
            // ask for every peer to send their blocks, if all file success, success and shutdown this thread.
            if (SearchServer(keywords, maxhits, idxAddress, idxPort, idxSecret)){
                tgui.logInfo("Successfully search from Idx Server!");
                tgui.logInfo("Search thread completed.");
                return;
            }
            // if download failed, return and print error message
            else {
                tgui.logWarn("Can search from Idx Server");
                tgui.logInfo("Search Request thread completed.");
                return;
            }
        }
    }



    private boolean SearchServer(String[] keywords, int maxhits, InetAddress idxAddress, int idxPort, String idxSecret) {
        // try to establish connection and handshake with idx server.
        ConnectServer connection = new ConnectServer(this.tgui);
        if (!connection.MakeConnection(idxAddress, idxPort, idxSecret)) return false;

        // Send current request to IDX server
        try {
            // Send search request and get message back
            connection.sendRequest(new SearchRequest(maxhits, keywords));
            Message search_back = connection.getMsg();
            // check if it's error message
            if (!checkReply(search_back)){
                tgui.logWarn("Unexpected Server Reply.");
                return false;
            }
            SearchReply searchReply = (SearchReply) search_back;

            // Add file info to GUI table
            IndexElement[] hits = searchReply.hits;
            Integer[] seedCounts = searchReply.seedCounts;
            // before add, remove previous history
            tgui.clearSearchHits();
            // iterate through list of returned request (All relevant file lists + number of sharer for each file)
            for (int i = 0; i < hits.length; i++) {
                // create new searchRecord class and add to our gui table.
                IndexElement ie = hits[i];
                SearchRecord newSearchRecord =
                        new SearchRecord(ie.fileDescr, seedCounts[i], idxAddress, idxPort, idxSecret, ie.secret);
                tgui.addSearchHit(ie.filename, newSearchRecord);
            }
            connection.shutdown();
            return true;
        } catch (JsonSerializationException e) {
            tgui.logError("JsonSerializationException");
            return false;
        } catch (IOException e) {
            tgui.logError("IO exception found");
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
