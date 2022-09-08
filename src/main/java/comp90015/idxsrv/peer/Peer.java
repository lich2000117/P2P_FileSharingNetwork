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
		if (! file.getParent().contains(basedir)){tgui.logWarn("Sharing File Not in Base Directory!"); return;}
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
			return;
		} catch (IOException e) {
			tgui.logError("IO operation failed! Cannot use FileMgr to create local file.");
			return;
		}
		// try to establish connection and handshake with idx server.
		ConnectServer connection = new ConnectServer(this.tgui);
		if (!connection.MakeConnection(idxAddress, idxPort, idxSecret)) return;

		// create and send request to share with idx server
		try{
			// send request
			connection.sendRequest(new ShareRequest(fileMgr.getFileDescr(), relativePathName, shareSecret, this.port));

			// receive reply from idx server
			Message msg_back = connection.getMsg();
			// check if it's error message
			if (!checkReply(msg_back)){tgui.logWarn("Something wrong with Server Side.");return;}

			// Add to GUI
			ShareReply reply = (ShareReply) msg_back;
			ShareRecord newRecord = new ShareRecord(fileMgr, reply.numSharers,"Ready", idxAddress,
					idxPort, idxSecret, shareSecret);
			tgui.addShareRecord(relativePathName, newRecord);
			connection.shutdown();
			fileMgr.closeFile();
			tgui.logInfo("shareFileWithIdxServer Finished!");
		}
		catch (FileNotFoundException e) {
			tgui.logError("File out of Directory!");
			return;
		} catch (JsonSerializationException e) {
			tgui.logError("JsonSerializationException");
			return;
		} catch (IOException e) {
			tgui.logError("IO operation failed! Abort");
			return;
		}
	}

	@Override
	public void searchIdxServer(String[] keywords,
			int maxhits,
			InetAddress idxAddress,
			int idxPort,
			String idxSecret) {
		// try to establish connection and handshake with idx server.
		ConnectServer connection = new ConnectServer(this.tgui);
		if (!connection.MakeConnection(idxAddress, idxPort, idxSecret)) return;

		// Send current request to IDX server
		try {
			// Send search request and get message back
			connection.sendRequest(new SearchRequest(maxhits, keywords));
			Message search_back = connection.getMsg();
			// check if it's error message
			if (!checkReply(search_back)){
				tgui.logError("Something Wrong with the Server Side.");
				return;
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
			tgui.logInfo("searchIdxServer Finished!");
		} catch (JsonSerializationException e) {
			tgui.logError("JsonSerializationException");
			return;
		} catch (IOException e) {
			tgui.logError("IO exception found");
			return;
		}
	}


	@Override
	public boolean dropShareWithIdxServer(String relativePathname, ShareRecord shareRecord) {

		// try to establish connection and handshake with idx server.
		ConnectServer connection = new ConnectServer(this.tgui);
		if (!connection.MakeConnection(shareRecord.idxSrvAddress, shareRecord.idxSrvPort, shareRecord.idxSrvSecret)) return false;

		// create and send request to share with server
		try {
			//send request
			connection.sendRequest(new DropShareRequest(relativePathname, shareRecord.fileMgr.getFileDescr().getFileMd5(),
					shareRecord.sharerSecret, this.port));
			// receive reply
			Message msg_back = connection.getMsg();
			// check if reply is a success flag to return false or true.
			if (!checkReply(msg_back)){tgui.logError("Something Wrong with the Server Side."); return false;}
			DropShareReply dropShareReply = (DropShareReply) msg_back;
			if (!dropShareReply.success) { tgui.logError("Server failed to drop the record"); return false;}

			connection.shutdown();
			shareRecord.fileMgr.closeFile();
			tgui.logInfo("Drop file success!");
			return true;
		}
		catch (Exception e) {
			//e.printStackTrace();
			tgui.logError("An error occured when Dropping file!");
			return false;
		}
	}

	@Override
	public void downloadFromPeers(String relativePathname, SearchRecord searchRecord) {
		// try to establish connection and handshake with index server.
		ConnectServer connection = new ConnectServer(this.tgui);
		if (!connection.MakeConnection(searchRecord.idxSrvAddress, searchRecord.idxSrvPort, searchRecord.idxSrvSecret)) return;

		// create a thread to ask for download and make it run.
		PeerDownloadThread downloadThread;
		try {
			downloadThread = new PeerDownloadThread(relativePathname, searchRecord, connection, tgui, timeout);
		} catch (IOException e) {
			tgui.logError("Downloading Thread Failed!");
			return;
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
