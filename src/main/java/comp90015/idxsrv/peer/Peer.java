package comp90015.idxsrv.peer;


import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingDeque;

import comp90015.idxsrv.message.*;
import comp90015.idxsrv.server.IOThread;
import comp90015.idxsrv.textgui.ISharerGUI;

/**
 * Skeleton Peer class to be completed for Project 1.
 * @author aaron
 * @modified Chenghao Li
 *
 */
public class Peer implements IPeer {

	public HashSet<String> sharingFileNames = new HashSet<String>();
	private IOThread ioThread;
	private PeerUpload_IOThread peerUploadIOThread;

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
		peerUploadIOThread = new PeerUpload_IOThread(this, incomingConnections,tgui, ioThread);
		peerUploadIOThread.start();
	}

	public void shutdown() throws InterruptedException, IOException {
		peerUploadIOThread.interrupt();
		peerUploadIOThread.join();
		ioThread.interrupt();
		ioThread.join();
	}


	@Override
	public void shareFileWithIdxServer(File file, InetAddress idxAddress, int idxPort, String idxSecret,
			String shareSecret) {
		// start a share Thread
		PeerShareThread shareThread = new PeerShareThread(this, basedir, file, idxAddress, idxPort, idxSecret, shareSecret, tgui, port);
		shareThread.start();
	}


	@Override
	public void searchIdxServer(String[] keywords,
			int maxhits,
			InetAddress idxAddress,
			int idxPort,
			String idxSecret) {
		PeerSearchThread searchThread = new PeerSearchThread(keywords, maxhits, idxAddress, idxPort, idxSecret, tgui);
		searchThread.start();
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
			this.sharingFileNames.remove(relativePathname);
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
