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
		peerUploadThread = new PeerUploadThread(port,incomingConnections,socketTimeout,tgui);
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

		if (downloadBlocksFromPeer(keywords, maxhits, idxAddress, idxPort, idxSecret, connection)) return;
		tgui.logInfo("searchIdxServer Finished!");
	}

	private boolean downloadBlocksFromPeer(String[] keywords, int maxhits, InetAddress idxAddress, int idxPort, String idxSecret, ConnectServer connection) {
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
				return true;
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
			return true;
		}
		return false;
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

		// Perform Look up to get a list of available resources
		// create and send request to share with server
		try {

			//get online sources.
			IndexElement[] sources = getSourcesFromIdx(relativePathname, searchRecord, connection);
			if (sources == null) {tgui.logWarn("No Available Sources"); return;}

			/* Load File */
			// A. Load temp File Create/Open Local unfinished file with FileMgr
			FileMgr tempFile = getLocalTempFile(relativePathname, searchRecord);
			int N = tempFile.getFileDescr().getNumBlocks(); //total number of blocks of the file
			tgui.logInfo("Total Number of Blocks to be downloaded: " + N);

			// get remained blocks required
			Set<Integer> remainedBlocksIdx = new HashSet<Integer>();
			remainedBlocksIdx.clear();
			getNeededBlockIdx(tempFile, N, remainedBlocksIdx);
			// check if we still need to download
			if (remainedBlocksIdx.size() == 0){tgui.logInfo("Local File exists, no need to download"); return;}

			tgui.logDebug("Waiting.....");
			//TimeUnit.SECONDS.sleep(3); // Wait for 5 seconds before connect to pper

			int peerCount = 0;
			// for every available peer, we try to download file from it
			for (IndexElement ie : sources) {
				peerCount += 1;
				/* Connect to Peer */
				// try to establish connection and handshake with peer server.
				Socket socket;

				BufferedWriter bufferedWriter;
				BufferedReader bufferedReader;
				try {
					socket = new Socket(ie.ip, ie.port);
					socket.setSoTimeout(10*1000);
					InputStream inputStream = socket.getInputStream();
					OutputStream outputStream = socket.getOutputStream();
					// initialise input and outputStream
					bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
					bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
					tgui.logInfo("Connecting to Peer: " + ie.ip + " : " + ie.port);

				} catch (Exception e) {
					e.printStackTrace();
					tgui.logInfo("Can NOT connect to Peer: " + ie.ip + " : " + ie.port);
					continue;
				}

				try {
					// for every needed block, send download request.
					for (Integer b : remainedBlocksIdx) {
						// if a block is successfully downloaded, print info
						// force skip first peer *************************************************************************************************
						if (peerUploadThread.singleBlockRequest(ie, tempFile, b, bufferedReader, bufferedWriter)) {
							tgui.logInfo("successfully downloaded block: " + b + " From Peer: " + ie.ip);
						}
						else{
							tgui.logInfo("Cannot downloaded block: " + b + " From Peer: " + ie.ip);
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					tgui.logInfo("Block Cannot be downloaded from " + ie.ip + " : " + ie.port);
					continue;
				}
				// close connection to current peer
				GoodByeToPeer(socket, bufferedReader, bufferedWriter);
				// after download, get still remained needed blocks index
				remainedBlocksIdx.clear();
				getNeededBlockIdx(tempFile, N, remainedBlocksIdx);
				// if no blocks needed, break loop.
				if (remainedBlocksIdx.size() == 0) {
					tgui.logInfo("Finish All Downloading! Number of Peer connected: " + peerCount);
					connection.shutdown();
					return;
				}
			}

			// if reach this line, no peer has required block, print info
			tgui.logWarn("Download Failed, No peer has following blocks: " + remainedBlocksIdx.toString());
			connection.shutdown();
			return;
		}
		catch (Exception e) {
			e.printStackTrace();
			tgui.logError("Download Error Occur!");
			return;
		}
	}

	private void GoodByeToPeer(Socket socket, BufferedReader bufferedReader, BufferedWriter bufferedWriter) throws IOException {
		//******************* finish Goodbye message *************
		try {
			// Send Finish GoodBye Signal
			writeMsg(bufferedWriter,new Goodbye());
			// receive GoodBye Signal
			Goodbye gb = (Goodbye) readMsg(bufferedReader);
			// close the streams
			bufferedReader.close();
			bufferedWriter.close();
			socket.close();
		} catch (Exception e1) {
			writeMsg(bufferedWriter, new ErrorMsg("Fail to exchange good bye signal"));
		}

		tgui.logInfo("GoodBye Exchanged With: " + socket.getInetAddress());
	}

	private void getNeededBlockIdx(FileMgr tempFile, int N, Set<Integer> remainedBlocksIdx) throws IOException, BlockUnavailableException {
		remainedBlocksIdx.clear();
		for(int b = 0; b< N; b++) {
			// Check Local Block exists completed? check if we have file already, no need to download.
			if (tempFile.isBlockAvailable(b)) {
				byte[] localBlockData = tempFile.readBlock(b);
				if (tempFile.checkBlockHash(b, localBlockData)) {
					//tgui.logInfo();
					continue; // next block
				}
			}
			// if not downloaded, add to our download list
			remainedBlocksIdx.add(b);
		}
	}

	/**
	 * create or load a local temporary file from disk
	 * @param relativePathname
	 * @param searchRecord
	 * @return
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	private FileMgr getLocalTempFile(String relativePathname, SearchRecord searchRecord) throws IOException, NoSuchAlgorithmException {
		// create DOWNLOAD directory for download
		(new File("DOWNLOAD/" + new File(relativePathname).getParent())).mkdirs();

		String downloadPath = new File("DOWNLOAD/", relativePathname).getPath();
		tgui.logError(downloadPath);
		FileMgr localTempFile = new FileMgr(downloadPath, searchRecord.fileDescr);
		return localTempFile;
	}

	/**
	 * Return a list of available Peer sources, IndexElement[]
	 * @param relativePathname
	 * @param searchRecord
	 * @param connection
	 * @return
	 * @throws IOException
	 * @throws JsonSerializationException
	 */
	private IndexElement[] getSourcesFromIdx(String relativePathname, SearchRecord searchRecord, ConnectServer connection) throws IOException, JsonSerializationException {
		//send request to get a file with same name and same MD5 code as file described in index server.
		Message msgToSend = new LookupRequest(relativePathname, searchRecord.fileDescr.getFileMd5());
		connection.sendRequest(msgToSend);
		// receive reply
		Message msg_back = connection.getMsg();
		// check if reply is a success flag to return false or true.
		if (!checkReply(msg_back)){
			return null;
		}
		LookupReply lookupReply = (LookupReply) msg_back;
		// get an array of available resources
		IndexElement[] sources = lookupReply.hits;
		tgui.logInfo("Get File Sources Success!");
		connection.shutdown();  // shutdown connection with Server
		return sources;
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
