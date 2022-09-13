import comp90015.idxsrv.peer.PeerSearchThread;
import comp90015.idxsrv.peer.PeerShareThread;

import java.io.File;
import java.lang.reflect.Array;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Random;

public class SendSpamSearchRequests {
    /**
     * Initialise Spam IDX_share threads, read local files in to reduce IO impact on execution time.
     * @param Address
     * @param port
     * @param NUM
     * @return
     */

    public static ArrayList<PeerSearchThread> getThreads(InetAddress Address, int port, int NUM){
        ArrayList<PeerSearchThread> out_threads = new ArrayList<PeerSearchThread>();
        String idxSecret = "server123";
        Random rand = new Random();
        PeerSearchThread testThread;

        for (int i=0;i<NUM;i++) {
            testThread = new PeerSearchThread(new String[]{String.valueOf(rand.nextInt(50000))}, 1000, Address, port, idxSecret);

            out_threads.add(testThread);
        }
        return out_threads;
    }
}
