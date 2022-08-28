package comp90015.idxsrv.peer;


import java.io.*;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingDeque;

import comp90015.idxsrv.filemgr.FileDescr;
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
		// try to establish connection and handshake.
		ConnectServer connection = new ConnectServer(this.tgui);
		if (!connection.MakeConnection(idxAddress, idxPort, idxSecret)) {
			tgui.logError("Connection Failed!");
			return;
		}

		// create and send request to share with server
		try{
			RandomAccessFile raFile = new RandomAccessFile(file, "r");
			FileDescr fd = new FileDescr(raFile);
			String fileName = file.getName();
			//send request
			Message msgToSend = new ShareRequest(fd, fileName, shareSecret, idxPort);
			connection.sendRequest(msgToSend);
			// receive reply
			Message msg_back = connection.getMsg();
			// check if it's error message
			if (!checkReply(msg_back)){return;}
			// if server accept, add to GUI
			FileMgr fileMgr = new FileMgr(fileName, fd);
			ShareReply reply = (ShareReply) msg_back;
			ShareRecord newRecord = new ShareRecord(fileMgr, reply.numSharers," ", idxAddress,
					idxPort, idxSecret, shareSecret);
			tgui.addShareRecord(file.getName(), newRecord);
			tgui.logInfo("shareFileWithIdxServer Finished!");
		}
		catch (Exception e) {
			//e.printStackTrace();
			tgui.logError("shareFileWithIdxServer Failed!");
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
			tgui.logError("Connection Failed!");
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
			if (!checkReply(searchReply)){return;};
			tgui.logInfo("searchReply Received!");
			IndexElement[] hits = searchReply.hits;
			Integer[] seedCounts = searchReply.seedCounts;
			// before add, remove previous history
			tgui.clearSearchHits();
			// iterate through list of returned request (All relevant file lists + number of sharer for each file)
			for (int i = 0; i < hits.length; i++) {
				IndexElement ie = hits[i];
				int curSeedCounts = seedCounts[i];
				String fileName = ie.filename;
				InetAddress inetAddress = InetAddress.getByName(ie.ip);

				// create new searchrecord class and add to our gui table.
				SearchRecord newSearchRecord =
						new SearchRecord(ie.fileDescr, curSeedCounts, inetAddress, ie.port, idxSecret, ie.secret);
				tgui.addSearchHit(fileName, newSearchRecord);
				tgui.logInfo("searchRecord Added!");
			}
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
			tgui.logError("Connection Failed!");
			return false;
		}

		// create and send request to share with server
		try {
			//send request
			Message msgToSend = new DropShareRequest(relativePathname, shareRecord.fileMgr.getFileDescr().getFileMd5(),
					shareRecord.sharerSecret, shareRecord.idxSrvPort);
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
			tgui.logError("Connection Failed!");
			return;
		}

		// Perform Look up to get a list of available resources
		// create and send request to share with server
		try {
			//send request
			Message msgToSend = new LookupRequest(relativePathname, searchRecord.fileDescr.getFileMd5());
			connection.sendRequest(msgToSend);
			// receive reply
			Message msg_back = connection.getMsg();
			// check if reply is a success flag to return false or true.
			if (!checkReply(msg_back)){return;}
			LookupReply lookupReply = (LookupReply) msg_back;
			// get an array of available resources
			IndexElement[] sources = lookupReply.hits;
			tgui.logInfo("Get File Sources Success!");
			return;
		}
		catch (Exception e) {
			//e.printStackTrace();
			tgui.logError("Get File Sources Failed!");
			return;
		}
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
