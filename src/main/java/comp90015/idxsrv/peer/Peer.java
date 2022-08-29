package comp90015.idxsrv.peer;


import java.io.*;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

import comp90015.idxsrv.filemgr.BlockUnavailableException;
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

	private PeerIOThread peerIoThread;

	private LinkedBlockingDeque<Socket> incomingConnections;

	private ISharerGUI tgui;

	private HashMap<String, ShareRecord> sharingFiles;  // MD5: ShareRecord  dictionary

	private String basedir;

	private int timeout;

	private int port;

	public Peer(int port, String basedir, int socketTimeout, ISharerGUI tgui) throws IOException {
		this.tgui=tgui;
		this.port=port;
		this.timeout=socketTimeout;
		this.basedir=new File(basedir).getCanonicalPath();
		this.sharingFiles = new HashMap<String, ShareRecord>();
		this.incomingConnections = new LinkedBlockingDeque<Socket>();
		peerIoThread = new PeerIOThread(port,incomingConnections,socketTimeout,tgui, sharingFiles);
		peerIoThread.start();
	}

	public void shutdown() throws InterruptedException, IOException {
		peerIoThread.shutdown();
		peerIoThread.interrupt();
		peerIoThread.join();
	}


	/**
	 * This method is essentially the "Session Layer" logic, where the session is
	 * short since it consists of exactly one request on the socket, then the socket
	 * is closed.
	 * Note:
	 * 		This method is specifically for downlaod requests sent from other peers, follow protocle:
	 * 		1. receive requested filename and block info
	 * 		2. receive sharer (our) secret.
	 * 		3. check secret and send success.
	 * 		4. send block data.
	 * @param socket
	 * @throws IOException
	 */
	private void processRequest(Socket socket) throws IOException {
		String ip=socket.getInetAddress().getHostAddress();
		int port=socket.getPort();
		tgui.logError("Client Upload processing request on connection "+ip);
		InputStream inputStream = socket.getInputStream();
		OutputStream outputStream = socket.getOutputStream();
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
		BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

		/*
		 * Follow the synchronous handshake protocol.
		 */

//		// 1. write the welcome
		writeMsg(bufferedWriter,new WelcomeMsg("welcome to peer sharer!"));
//
////		// 2. get a message with block info
		Message msg;
		try {
			msg = readMsg(bufferedReader);
		} catch (JsonSerializationException e1) {
			writeMsg(bufferedWriter,new ErrorMsg("Invalid message"));
			return;
		}
		tgui.logInfo(msg.toString());
	}

	/*
	 * Methods to process each of the possible requests.
	 */
	private void processDownloadRequest(BufferedWriter bufferedWriter,BlockRequest msg, String ip, int port) throws IOException {
		tgui.logError("Ready To Download!");

		// *********** send a block reply here!!   *******************************
		//writeMsg(bufferedWriter,new ErrorMsg("Download Finished to last step on peer sharer."));

//		// check if requested block is the same as sharing block.
//		if (!(fileMgr.getFileDescr().getBlockMd5(msg.blockIdx) == msg.fileMd5)) {
//			writeMsg(bufferedWriter,new ErrorMsg("File Block Unmatched!"));
//		}
//		// else, access local block file and send blockreply
//		if (fileMgr.isBlockAvailable(msg.blockIdx)) {
//			try {
//				byte[] data = fileMgr.readBlock(msg.blockIdx);
//				writeMsg(bufferedWriter,new BlockReply(msg.filename, fileMgr.getFileDescr().getFileMd5(), msg.blockIdx, FileDescr.bytesToHex(data)));
//			}
//			catch (BlockUnavailableException e) {
//				writeMsg(bufferedWriter,new ErrorMsg("Block is not available!"));
//			}
//		}
	}



	/*
	 * Students are to implement the interface below.
	 */

	@Override
	public void shareFileWithIdxServer(File file, InetAddress idxAddress, int idxPort, String idxSecret,
			String shareSecret) {
		// Check if file in base dir
		if (! file.getParent().equals(basedir)){tgui.logError("File Not in Base Directory!"); return;}

		// try to establish connection and handshake.
		ConnectServer connection = new ConnectServer(this.tgui);
		if (!connection.MakeConnection(idxAddress, idxPort, idxSecret)) {
			tgui.logError("Connection Failed!");
			return;
		}

		// create and send request to share with server
		try{
			RandomAccessFile raFile = new RandomAccessFile(file, "r");
			String fileName = file.getName();
			FileMgr fileMgr = new FileMgr(fileName);
			//send request
			Message msgToSend = new ShareRequest(fileMgr.getFileDescr(), fileName, shareSecret, this.port);
			connection.sendRequest(msgToSend);
			// receive reply
			Message msg_back = connection.getMsg();
			// check if it's error message
			if (!checkReply(msg_back)){return;}
			// if server accept, add to GUI
			ShareReply reply = (ShareReply) msg_back;
			ShareRecord newRecord = new ShareRecord(fileMgr, reply.numSharers," ", idxAddress,
					idxPort, idxSecret, shareSecret);
			tgui.addShareRecord(file.getName(), newRecord);
			tgui.logInfo("shareFileWithIdxServer Finished!");
			sharingFiles.put(newRecord.fileMgr.getFileDescr().getFileMd5(), newRecord); // add to our hashmap dictionary
			//this.run();  // start listening thread
		}
		catch (FileNotFoundException e) {
			tgui.logError("File out of Directory!");
			return;
		}
		catch (Exception e) {
			tgui.logError(e.toString());
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
						new SearchRecord(ie.fileDescr, curSeedCounts, inetAddress, idxPort, idxSecret, ie.secret);
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
			//send request to get a file with same name and same MD5 code as file described in index server.
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
			connection.shutdown();  // shutdown connection with Server


			/* Choose Online Resources */
			// hard code to use first resources.
			IndexElement source = sources[0];
			tgui.logInfo(source.ip);
			tgui.logInfo(String.valueOf(source.port));


			/* Load File */
			// A. Load Local File Create/Open Local unfinished file with FileMgr
			FileMgr localTempFile = new FileMgr("TEMP_" + relativePathname, searchRecord.fileDescr);
			//FileMgr localTempFile = new FileMgr(relativePathname, searchRecord.fileDescr);
			int blockIdx_Need = 0;

			/**Get Blocks need to be done here**/
			// B. Check Local Block exists completed? check if we have file already, no need to connect to peer or download.
			if (localTempFile.isBlockAvailable(blockIdx_Need)) {
				byte[] localBlockData = localTempFile.readBlock(blockIdx_Need);
				if (localTempFile.checkBlockHash(blockIdx_Need, localBlockData)) {
					tgui.logInfo("Local Block is already done, Skip Download");
					return;
				}
			}

			/* Connect to Peer */
			// try to establish connection and handshake with peer server.
			Socket socket = new Socket(source.ip, source.port);
			tgui.logError(source.ip + " : " + source.port);
			InputStream inputStream = socket.getInputStream();
			OutputStream outputStream = socket.getOutputStream();
			// initialise input and outputStream
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
			BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
			tgui.logError("Connection to Peer Succeed!!");


			/**Iterate through every Block need to be done here**/
			/*
			 1. send a block request with filename first.
			 2. send another request with the sharer secret
			 3. get msg from peer back indicate success or not.
			 4. read block reply from peer server.
			*/

			// 2. (HandShake 1): Send Block request
			writeMsg(bufferedWriter, new BlockRequest(source.filename, source.fileDescr.getFileMd5(), blockIdx_Need));
			tgui.logError("haha");

			// 2.1 Get a Welcome Message
			Message welcome_msg = readMsg(bufferedReader);
			tgui.logInfo(welcome_msg.toString());


			//2.2. (HandShake 2): Send Key authenticate to server
			writeMsg(bufferedWriter, new AuthenticateRequest(source.secret));
			tgui.logError("Sent" + source.secret);

			//3. (HandShake 3): Check authenticate reply from server
			Message auth_back = readMsg(bufferedReader);
			if (auth_back.getClass().getName() == AuthenticateReply.class.getName()) {
				AuthenticateReply reply = (AuthenticateReply) auth_back;
				if (reply.success != true) {
					tgui.logError("Sharing Peer Authentication Failed! Check your secret with that file record.");
					return;
				}
			}

			// 4. download block files.
			Message msg = readMsg(bufferedReader);
			if (!(msg.getClass().getName() == BlockReply.class.getName())) { tgui.logError("Invalid Message");
				return;
			}


			// 5. Check Block Hash, see if the block we want is the same as received using MD5
			BlockReply block_reply = (BlockReply) msg;
			if (!(localTempFile.checkBlockHash(blockIdx_Need, block_reply.bytes.getBytes()))){
				tgui.logError("Received Block is not the one we want");
				return;
			}

			// 6. Write to Local File's block with FileMgr
			if (localTempFile.writeBlock(blockIdx_Need, block_reply.bytes.getBytes())){
				tgui.logInfo("Received Block written to File!");
			}
			else{
				tgui.logError("Received Block Not written to File!");
				return;
			}

			//******************* finish Goodbye message *************
			try {
				// Send Finish GoodBye Signal
				writeMsg(bufferedWriter,new Goodbye());
				// receive GoodBye Signal
				Goodbye gb = (Goodbye) readMsg(bufferedReader);
				// close the streams
				bufferedReader.close();
				bufferedWriter.close();
			} catch (Exception e1) {
				writeMsg(bufferedWriter, new ErrorMsg("Fail to exchange good bye signal"));
			}

			tgui.logInfo("Peer Finish Download!");

		//			// open local maybe unifinished target file, with remote file info.
		//			FileMgr local_file = new FileMgr(relativePathname, source.fileDescr);
		//			int n_blocks = source.fileDescr.getNumBlocks();
		//
		//			Set<Integer> blocksRequired;
		//			blocksRequired.clear();
		//			blocksDone.clear();
		//			for(int b=0;b<n_blocks;b++) {
		//				byte[] blockBytes = _readBlock(b);
		//				if(checkBlockHash(b,blockBytes)) {
		//					blocksDone.add(b);
		//				} else {
		//					blocksRequired.add(b);
		//				}
		//			}
		//
		//			// iterate blocks
		//			for (int i=0; i<n_blocks; i++){
		//				remote_file.
		//			}

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
