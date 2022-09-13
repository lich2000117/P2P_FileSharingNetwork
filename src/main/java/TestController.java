import comp90015.idxsrv.peer.PeerSearchThread;
import comp90015.idxsrv.peer.PeerShareThread;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;

public class TestController {

    static int TEST_PEERS = 50;
    static int FILE_SIZE = 523;
    static int num_success=0;

    /**
     * Test Controller, Initialise test using this class.
     * @param args
     * @throws IOException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        Share_Test();
        //Search_Test();
        long mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.println("Memory Usage: " + mem*0.000001 + " MB");

    }


    /**
     * File sharing stress test, fake number of peers (socket connections) and send share requests
     *  with randomly created local file.
     *  Initialise share thread in the background.
     *  Start the timer when every thread starts and calculate elapsed time until all thread finished.
     * @throws IOException
     * @throws InterruptedException
     */
    private static void Share_Test() throws IOException, InterruptedException {
        num_success = 0;
        // create random local file
        CreateRandomFiles.create(TEST_PEERS, FILE_SIZE);

        // get share thread running
        ArrayList<PeerShareThread> threads = SendSpamShareRequests.getThreads(InetAddress.getByName("localhost"), 3200, TEST_PEERS);
        long start = System.nanoTime();
        for (PeerShareThread t: threads) {
            t.start();
        }

        for (PeerShareThread t: threads) {
            t.join();
            if (t.success) num_success += 1;
        }

        long finish = System.nanoTime();
        long timeElapsed = finish - start;
        System.out.println("Milliseconds Execution: " + timeElapsed/1000000.0);
        System.out.println("Success Rate: " + num_success/((float) TEST_PEERS));
    }

    private static void Search_Test() throws IOException, InterruptedException {
        num_success = 0;
        // create random local file
        CreateRandomFiles.create(TEST_PEERS, FILE_SIZE);

        // get share thread running
        ArrayList<PeerSearchThread> threads = SendSpamSearchRequests.getThreads(InetAddress.getByName("localhost"), 3200, TEST_PEERS);
        long start = System.nanoTime();
        for (PeerSearchThread t: threads) {
            t.start();
        }

        for (PeerSearchThread t: threads) {
            t.join();
            if (t.success) num_success += 1;
        }

        long finish = System.nanoTime();
        long timeElapsed = finish - start;
        System.out.println("Milliseconds Execution: " + timeElapsed/1000000.0);
        System.out.println("Success Rate: " + num_success/((float) TEST_PEERS));
    }

}
