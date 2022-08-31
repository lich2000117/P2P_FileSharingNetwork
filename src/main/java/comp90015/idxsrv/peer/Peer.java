package comp90015.idxsrv.peer;


import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
import comp90015.idxsrv.filemgr.FileMgr;
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
	private PeerUploadThread peerUploadThread;

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
		this.incomingConnections = new LinkedBlockingDeque<Socket>();
		ioThread = new IOThread(port,incomingConnections,socketTimeout,tgui);
		ioThread.start();
		peerUploadThread = new PeerUploadThread(port,incomingConnections,socketTimeout,tgui, ioThread);
		peerUploadThread.start();
	}

	public void shutdown() throws InterruptedException, IOException {
		peerUploadThread.interrupt();
		peerUploadThread.join();
		ioThread.interrupt();
		ioThread.join();
	}


	@Override
	public void shareFileWithIdxServer(File file, InetAddress idxAddress, int idxPort, String idxSecret,
			String shareSecret) {
		// Check if file in base dir
		if (! file.getParent().contains(basedir)){tgui.logError("File Not in Base Directory!"); return;}

		// try to establish connection and handshake.
		ConnectServer connection = new ConnectServer(this.tgui);
		if (!connection.MakeConnection(idxAddress, idxPort, idxSecret)) {
			tgui.logError("Connection to IdxServer Failed!");
			return;
		}

		// create and send request to share with server
		try{
			RandomAccessFile raFile = new RandomAccessFile(file, "r");
			String filePath = file.getPath();
			String relativePathName = new File(basedir).toURI().relativize(new File(filePath).toURI()).getPath();

			FileMgr fileMgr = new FileMgr(filePath);

			//send request
			Message msgToSend = new ShareRequest(fileMgr.getFileDescr(), relativePathName, shareSecret, this.port);

			connection.sendRequest(msgToSend);

			// receive reply
			Message msg_back = connection.getMsg();

			// check if it's error message
			if (!checkReply(msg_back)){return;}

			// if server accept, add to GUI
			ShareReply reply = (ShareReply) msg_back;
			ShareRecord newRecord = new ShareRecord(fileMgr, reply.numSharers," ", idxAddress,
					idxPort, idxSecret, shareSecret);

			tgui.addShareRecord(relativePathName, newRecord);
			//tgui.logInfo("shareFileWithIdxServer Finished!");
			connection.shutdown();
		}
		catch (FileNotFoundException e) {
			tgui.logError("File out of Directory!");
			return;
		}
		catch (Exception e) {
			tgui.logError("shareFileWithIdxServer Failed!");
			e.printStackTrace();
			return;
		}
	}

	@Override
	public void searchIdxServer(String[] keywords,
			int maxhits,
			InetAddress idxAddress,
			int idxPort,
			String idxSecret) {
		// try to establish connection and handshake.
		ConnectServer connection = new ConnectServer(this.tgui);
		if (!connection.MakeConnection(idxAddress, idxPort, idxSecret)) {
			tgui.logError("Connection to Idx Server Failed!");
			return;
		}

		// Go on with current request
		try {
			// Send search request
			Message msgToSend = new SearchRequest(maxhits, keywords);
			connection.sendRequest(msgToSend);
			// Add file info to GUI table
			Message search_back = connection.getMsg();
			SearchReply searchReply = (SearchReply) search_back;
			// check if it's error message
			if (!checkReply(searchReply)){
				return;
			};
			//tgui.logInfo("searchReply Received!");
			IndexElement[] hits = searchReply.hits;
			Integer[] seedCounts = searchReply.seedCounts;
			// before add, remove previous history
			tgui.clearSearchHits();
			// iterate through list of returned request (All relevant file lists + number of sharer for each file)
			for (int i = 0; i < hits.length; i++) {
				IndexElement ie = hits[i];
				int curSeedCounts = seedCounts[i];
				String fileName = ie.filename;

				// create new searchrecord class and add to our gui table.
				SearchRecord newSearchRecord =
						new SearchRecord(ie.fileDescr, curSeedCounts, idxAddress, idxPort, idxSecret, ie.secret);
				tgui.addSearchHit(fileName, newSearchRecord);
				//tgui.logInfo("searchRecord Added!");
			}
			connection.shutdown();
		}
		catch (Exception e) {
			//e.printStackTrace();
			tgui.logError("searchIdxServer Failed!");
			return;
		}
		tgui.logInfo("searchIdxServer Finished!");
	}


	@Override
	public boolean dropShareWithIdxServer(String relativePathname, ShareRecord shareRecord) {

		// try to establish connection and handshake with server.
		ConnectServer connection = new ConnectServer(this.tgui);
		if (!connection.MakeConnection(shareRecord.idxSrvAddress, shareRecord.idxSrvPort, shareRecord.idxSrvSecret)) {
			tgui.logError("Connection to Idx Server Failed!");
			return false;
		}

		// create and send request to share with server
		try {
			//send request
			Message msgToSend = new DropShareRequest(relativePathname, shareRecord.fileMgr.getFileDescr().getFileMd5(),
					shareRecord.sharerSecret, this.port);
			connection.sendRequest(msgToSend);
			// receive reply
			Message msg_back = connection.getMsg();
			// check if reply is a success flag to return false or true.
			if (!checkReply(msg_back)){return false;}
			DropShareReply dropShareReply = (DropShareReply) msg_back;
			if (!dropShareReply.success) {
				return false;
			}
			tgui.logInfo("Drop file success!");
			connection.shutdown();
			return true;
		}
		catch (Exception e) {
			//e.printStackTrace();
			tgui.logError("Drop file Failed!");
			return false;
		}
	}

	@Override
	public void downloadFromPeers(String relativePathname, SearchRecord searchRecord) {
		// try to establish connection and handshake with index server.
		ConnectServer connection = new ConnectServer(this.tgui);
		if (!connection.MakeConnection(searchRecord.idxSrvAddress, searchRecord.idxSrvPort, searchRecord.idxSrvSecret)) {
			tgui.logError("Connection with Idx Server Failed!");
			return;
		}

		// create a thread to ask for download and make it run.
		PeerDownloadThread downloadThread;
		try {
			downloadThread = new PeerDownloadThread(relativePathname, searchRecord, connection, tgui);
		} catch (IOException e) {
			tgui.logError("Downloading Thread Failed!");
			throw new RuntimeException(e);
		}
		downloadThread.start();
	}





	/*

	 */

	/*
	check the reply from server, if it's error message, return false, print to console.
	Otherwise return true to indicate reply is valid.
	 */
	private boolean checkReply(Message msg_back){
		if (msg_back.getClass().getName() == ErrorMsg.class.getName()) {
			tgui.logError(((ErrorMsg) msg_back).msg);
			return false;
		}
		return true;
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
