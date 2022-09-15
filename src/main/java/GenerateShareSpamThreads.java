import comp90015.idxsrv.peer.PeerShareThread;

import java.io.*;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Random;

public class GenerateShareSpamThreads {
    /**
     * Initialise Spam threads to simulate peers sending share requests to index server,
     * read local files and create threads before, to reduce IO impact on execution time.
     */

    public static ArrayList<PeerShareThread> getThreads(InetAddress Address, int port, int NUM){
        // establish threads
        ArrayList<PeerShareThread> out_threads = new ArrayList<PeerShareThread>();
        Random rand = new Random();
        PeerShareThread testShareThread;

        for (File f : ReadFiles(NUM) ) {
            testShareThread = new PeerShareThread("", f, Address, port, "server123",
                    String.valueOf(rand.nextInt(50000)), rand.nextInt(30000));
            out_threads.add(testShareThread);
        }
        return out_threads;
    }
    private static ArrayList<File> ReadFiles(int NUM){
        // read in local files
        ArrayList<File> out = new ArrayList<>();
        for (int i = 0; i < NUM; i++) {
            File file = new File("RANDOM/random" + i + ".txt");
            out.add(file);
        }
        return out;
    }
}
