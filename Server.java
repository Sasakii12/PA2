import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Server {
    private static final int PORT = 12000;
    private static final Path FILE_DIR = Paths.get("").toAbsolutePath();
    private static final int MIN_CHUNKS = 100;
    private static final long MAX_CHUNK_BYTES = 1024 * 1024;

    public static void main(String[] args) throws IOException {
        Files.createDirectories(FILE_DIR);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("The server is ready to receive (serving files from "
                    + FILE_DIR.toAbsolutePath() + ")");

            while (true) {
                try (Socket socket = serverSocket.accept()) {
                    handleClient(socket);
                } catch (IOException e) {
                    System.out.println("Connection error: " + e.getMessage());
                }
            }
        }
    }

    private static void handleClient(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        // Client: "rdy"  ->  Server: "rdy"
        if (!in.readUTF().equals("rdy")) {
            System.out.println("Bad handshake, dropping client");
            return;
        }
        out.writeUTF("rdy");
        out.flush();

        String fileName = in.readUTF();
        System.out.println("Client requested: " + fileName);
        out.writeUTF(fileName);
        out.flush();

        // Sends the number of chunks
        if (!in.readUTF().equals("chunks")) {
            System.out.println("Expected a chunk count request, dropping client");
            return;
        }

        Path file = resolveFile(fileName);
        long fsize = (file == null) ? -1 : Files.size(file);
        int numChunks = (fsize < 0) ? -1 : chunkCount(fsize);
        out.writeInt(numChunks);
        out.flush();

        // Server sends chunks in random order
        if (numChunks > 0) {
            if (in.readUTF().equals("rdyD")) {
                boolean finished = sendChunksRandomly(file, fsize, numChunks, in, out);
                if (!finished) {
                    return; 
                }
            }
        }

        // Server closes the connection
        if (in.readUTF().equals("close")) {
            System.out.println("Client closed the session");
        }
    }

    // Sends every chunk exactly once in shuffled order, returns false if the client aborts.
    private static boolean sendChunksRandomly(Path file, long fsize, int numChunks,
                                              DataInputStream in, DataOutputStream out) throws IOException {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < numChunks; i++) {
            order.add(i);
        }
        Collections.shuffle(order);

        long chunkSize = chunkSize(fsize, numChunks);

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            for (int k = 0; k < numChunks; k++) {
                int index = order.get(k);
                long start = Math.min(index * chunkSize, fsize);
                long end = Math.min((index + 1) * chunkSize, fsize);

                byte[] data = new byte[(int) (end - start)];
                raf.seek(start);
                raf.readFully(data);

                out.writeInt(index);
                out.writeInt(data.length);
                out.write(data);
                out.flush();
                System.out.println("Sent chunk " + index + " (" + data.length + " bytes) [" + (k + 1) + "/" + numChunks + "]");

                if (k < numChunks - 1) {
                    String msg = in.readUTF();
                    if (!msg.equals("next")) {
                        System.out.println("Client sent '" + msg + "' instead of 'next', stopping");
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int chunkCount(long fsize) {
        long bySize = (fsize + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
        return (int) Math.max(MIN_CHUNKS, bySize);
    }

    private static long chunkSize(long fsize, int numChunks) {
        return (fsize + numChunks - 1) / numChunks;
    }

    // Returns the file inside FILE_DIR, or null if it doesn't exist
    private static Path resolveFile(String fileName) {
        try {
            Path name = Paths.get(fileName).getFileName();
            if (name == null) return null;
            Path file = FILE_DIR.resolve(name);
            return Files.isRegularFile(file) ? file : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
