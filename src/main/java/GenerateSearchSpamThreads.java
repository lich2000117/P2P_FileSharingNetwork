import comp90015.idxsrv.peer.PeerSearchThread;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Random;

public class GenerateSearchSpamThreads {
    /**
     * Initialise Spam threads to simulate peers sending search requests to index server,
     * create threads before, to reduce IO impact on execution time.
     */

    public static ArrayList<PeerSearchThread> getThreads(InetAddress Address, int port, int NUM){
        // generate threads
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
