import comp90015.idxsrv.message.*;
import comp90015.idxsrv.peer.PeerShareThread;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Random;

public class SendSpamShareRequests {
    /**
     * Initialise Spam IDX_share threads, read local files in to reduce IO impact on execution time.
     * @param Address
     * @param port
     * @param NUM
     * @return
     */

    public static ArrayList<PeerShareThread> getThreads(InetAddress Address, int port, int NUM){
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
    private static
    ArrayList<File> ReadFiles(int NUM){
        ArrayList<File> out = new ArrayList<>();
        for (int i = 0; i < NUM; i++) {
            File file = new File("RANDOM/random" + i + ".txt");
            out.add(file);
        }
        return out;
    }
}
