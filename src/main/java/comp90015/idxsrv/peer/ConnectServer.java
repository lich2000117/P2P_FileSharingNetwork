package comp90015.idxsrv.peer;

import comp90015.idxsrv.message.*;
import comp90015.idxsrv.textgui.ISharerGUI;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * This class makes connection to Idx Server and send/receive messages from it.
 *
 * @author Chenghao Li
 *
 */
public class ConnectServer {
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private ISharerGUI tgui;
    private BufferedReader bufferedReader;
    private BufferedWriter bufferedWriter;

    public ConnectServer(ISharerGUI tgui){
        this.tgui = tgui;
    }

    /**
     * Make a socket Connection towards target Index Server.
     * Should Follow the same protocol with Server:
         * 1. establish socket connection.
         * 2. send authentication request;
         * 2.1. get welcome message from server;
         * 3. receive confirm reply from server after authenticate.
     */
    public boolean MakeConnection(InetAddress Address, int Port, String Secret){
        try {
            // 1. (Initialise) Create Socket
            tgui.logInfo("Trying to connect, Timeout = " + 10 + " seconds");
            this.socket = new Socket();
            socket.connect(new InetSocketAddress(Address, Port), 10*1000);
            this.inputStream = this.socket.getInputStream();
            this.outputStream = this.socket.getOutputStream();
            // initialise input and outputStream
            this.bufferedReader = new BufferedReader(new InputStreamReader(this.inputStream, StandardCharsets.UTF_8));
            this.bufferedWriter = new BufferedWriter(new OutputStreamWriter(this.outputStream, StandardCharsets.UTF_8));

            /* Suppose to Follow a Synchronized protocol with Server */
            // 2. (HandShake 1): Write an authentication message to establish authenticated message
            writeMsg(bufferedWriter, new AuthenticateRequest(Secret));

            // 2.1 Get a Welcome Message
            Message welcome_msg = readMsg(bufferedReader);

            // 3. (HandShake 2): Check authenticate reply from server
            Message auth_back = readMsg(bufferedReader);
            if (auth_back.getClass().getName() == AuthenticateReply.class.getName()) {
                AuthenticateReply reply = (AuthenticateReply) auth_back;
                if (reply.success != true) {
                    tgui.logError("ServerSide Authentication Failed! Check your secret with Index Server.");
                    return false;
                }
            }
            tgui.logInfo("Successfully connected to index server!");
            return true;
        }
        catch (Exception e){
            tgui.logWarn("Failed to connect to index server!");
            return false;
        }
    }

    /*
    Shutdown current connection.
     */
    public void shutdown() throws IOException {
        tgui.logInfo("Connection to Idx Server Closed!");
        this.socket.close();
    }

    /*
     * Send and Receive a Message object in current connection.
     */
    public void sendRequest(Message msg) throws IOException {
        this.writeMsg(this.bufferedWriter, msg);
    }

    public Message getMsg() throws JsonSerializationException, IOException {
        return readMsg(this.bufferedReader);
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
