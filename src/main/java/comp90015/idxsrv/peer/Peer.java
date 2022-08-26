package comp90015.idxsrv.peer;


import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingDeque;

import comp90015.idxsrv.filemgr.FileDescr;
import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IOThread;
import comp90015.idxsrv.server.IndexElement;
import comp90015.idxsrv.textgui.ISharerGUI;

/**
 * Skeleton Peer class to be completed for Project 1.
 * @author aaron
 *
 */
public class Peer implements IPeer {

	private IOThread ioThread;

	private LinkedBlockingDeque<Socket> incomingConnections;

	private ISharerGUI tgui;

	private String basedir;

	private int timeout;

	private int port;

	public Peer(int port, String basedir, int socketTimeout, ISharerGUI tgui) throws IOException {
		this.tgui=tgui;
		this.port=port;
		this.timeout=socketTimeout;
		this.basedir=new File(basedir).getCanonicalPath();
		ioThread = new IOThread(port,incomingConnections,socketTimeout,tgui);
		ioThread.start();
	}

	public void shutdown() throws InterruptedException, IOException {
		ioThread.shutdown();
		ioThread.interrupt();
		ioThread.join();
	}

	/*
	 * Students are to implement the interface below.
	 */

	@Override
	public void shareFileWithIdxServer(File file, InetAddress idxAddress, int idxPort, String idxSecret,
			String shareSecret) {
		tgui.logError("shareFileWithIdxServer unimplemented");
	}

	@Override
	public void searchIdxServer(String[] keywords,
			int maxhits,
			InetAddress idxAddress,
			int idxPort,
			String idxSecret) {
		Socket socket;
		try {
			// 1. (Initialise) Create Socket
			socket = new Socket(idxAddress, idxPort);
			tgui.logInfo("Socket Created!");

			// initialise input and outputStream
			InputStream inputStream = socket.getInputStream();
			OutputStream outputStream = socket.getOutputStream();
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
			BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

			// 2. (HandShake 1): Write an authenticate message to establish authenticated message
			writeMsg(bufferedWriter, new AuthenticateRequest(idxSecret));

			// 2.1 Get a Welcome Message
			Message welcome_msg = readMsg(bufferedReader);
			tgui.logInfo(welcome_msg.toString());

			// 3. (HandShake 2): Check authenticate reply from server
			Message auth_back = readMsg(bufferedReader);
			if (auth_back.getClass().getName()==AuthenticateReply.class.getName()){
				AuthenticateReply reply= (AuthenticateReply) auth_back;
				if (reply.success!=true){
					tgui.logError("ServerSide Authentication Failed! Check your secret with that server.");
					return;
				}
			}

			// 4. (Send) Write a message to request search
			writeMsg(bufferedWriter, new SearchRequest(maxhits, keywords));
			tgui.logInfo("Search Request Sent!");

			// 5. (Receive) Get a search reply and Extract all files info sent from server
			Message search_back = readMsg(bufferedReader);
			tgui.logInfo(search_back.getClass().getName());
			if (search_back.getClass().getName()==SearchReply.class.getName()) {
				SearchReply searchReply = (SearchReply) search_back;
				tgui.logInfo("searchReply Received!");
				IndexElement[] hits = searchReply.hits;
				Integer[] seedCounts = searchReply.seedCounts;
				// iterate through list of returned request (All relevant file lists + number of sharer for each file)
				for(int i=0;i<hits.length;i++) {
					IndexElement ie = hits[i];
					int curSeedCounts = seedCounts[i];
					String fileName = ie.filename;
					InetAddress inetAddress = InetAddress.getByName(ie.ip);
					// create new searchrecord class
					SearchRecord newSearchRecord =
							new SearchRecord(ie.fileDescr, curSeedCounts, inetAddress, ie.port, idxSecret, ie.secret);
					// add to our gui table.
					tgui.addSearchHit(fileName, newSearchRecord);
					tgui.logInfo("searchRecord Added!");
				}
			}
			// 6. (END) finish the method
			tgui.logInfo("searchIdxServer Finished!");
			return;
		} catch (Exception e) {
			e.printStackTrace();
		}
		tgui.logError("searchIdxServer Failed!!");
	}

	@Override
	public boolean dropShareWithIdxServer(String relativePathname, ShareRecord shareRecord) {
		tgui.logError("dropShareWithIdxServer unimplemented");
		return false;
	}

	@Override
	public void downloadFromPeers(String relativePathname, SearchRecord searchRecord) {
		tgui.logError("downloadFromPeers unimplemented");
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
