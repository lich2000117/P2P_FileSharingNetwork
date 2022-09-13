/**
 * Code Used: http://stackoverflow.com/questions/30284921/creating-a-text-file-filled-with-random-integers-in-java
 * Modified by: Chenghao Li
 */
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
public class CreateRandomFiles {
    /**
     * Create NUM number of local files with size FileSize
     * @param NUM
     * @param FileSize
     * @throws IOException
     */
    public static void create(int NUM, int FileSize) throws IOException {
        Files.createDirectories(Paths.get("RANDOM/"));
        for (int i = 0; i < NUM; i++) {
            File file = new File("RANDOM/random" + i + ".txt");
            FileWriter writesToFile = null;
            try {
                // Create file writer object
                writesToFile = new FileWriter(file);
                // Wrap the writer with buffered streams
                BufferedWriter writer = new BufferedWriter(writesToFile);
                int line;
                Random rand = new Random();
                for (int j = 0; j < FileSize; j++) {
                    // Randomize an integer and write it to the output file
                    line = rand.nextInt(50000);
                    writer.write(line + "\n");
                }
                // Close the stream
                writer.close();
            }
            catch (IOException e) {
                e.printStackTrace();
                System.exit(0);
            }
        }
    }
}