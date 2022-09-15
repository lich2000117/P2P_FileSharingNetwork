package comp90015.idxsrv.peer;

import comp90015.idxsrv.filemgr.FileMgr;
import comp90015.idxsrv.message.BlockReply;
import comp90015.idxsrv.textgui.ISharerGUI;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * This thread invokes by Downloading thread.
 * It has a queue take incoming block reply and write them sequentially to local.
 *
 * This thread's status is checked once the peer want to verify if local file is finished downloading.
 *
 * @author Chenghao Li
 */
public class BlockWriteThread extends Thread {
    private ISharerGUI tgui;
    public LinkedBlockingDeque<BlockReply> incomingWriteBlocks;
    private FileMgr tempFile;

    /**
     * Create a Peer Download Thread,
     *
     */
    public BlockWriteThread(FileMgr tempFile, ISharerGUI tgui, LinkedBlockingDeque<BlockReply> incomingWriteBlocks){
        this.tempFile = tempFile;
        this.tgui = tgui;
        this.incomingWriteBlocks = incomingWriteBlocks;
    }

    @Override
    public void run() {
        tgui.logInfo("Writing thread running");

        while (!isInterrupted()) {
            BlockReply msg;
            try {
                msg = incomingWriteBlocks.take();
                if (SingleBlockWrite(tempFile, msg)) {
                    //tgui.logInfo("Block written successful.");
                }
                // if download failed, return and print error message
                else {
                    tgui.logWarn("Can not write block.");
                }
            } catch (InterruptedException e) {
                tgui.logWarn("Writer Thread interrupted.");
                break;
            }
        }
        tgui.logInfo("Downloading thread completed.");
    }

    private boolean SingleBlockWrite(FileMgr tempFile, BlockReply msg) {
        // Check Block Hash, see if the block we want is the same as received using MD5
        try {
            BlockReply block_reply = msg;
            int blockIdx = block_reply.blockIdx;
            byte[] receivedData = Base64.getDecoder().decode(new String(block_reply.bytes).getBytes("UTF-8"));
            if (!(tempFile.checkBlockHash(blockIdx, receivedData))) {
                tgui.logError("Received Block is not the one we want");
                return false;
            }

            // Write to Local File's block with FileMgr
            if (tempFile.writeBlock(blockIdx, receivedData)) {
                tgui.logInfo("Received Block " + blockIdx);
            } else {
                tgui.logError("Received Block " + blockIdx);
                return false;
            }
        }
        catch (IOException e) {
            tgui.logError("IO exception when writing to File!");
            return false;
        }
        return true;
    }

}