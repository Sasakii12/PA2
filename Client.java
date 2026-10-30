import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class Client {
    private static final String SERVER_NAME = "127.0.0.1";
    private static final int SERVER_PORT = 12000;
    private static final Path DOWNLOAD_DIR = Paths.get("downloads");

    // Limit number of bytes on a single chunk that the client will accept
    private static final int MAX_CHUNK_BYTES = 64 * 1024 * 1024; 

    public static void main(String[] args) throws IOException {
        // Prompt for filename or accept through cli arguments
        String fileName;

        if (args.length > 0) {
            fileName = args[0];
        } else {
            System.out.print("Input file name to download (with extension): ");
            fileName = new Scanner(System.in).nextLine().trim();
        }

        try (Socket socket = new Socket(SERVER_NAME, SERVER_PORT)) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // Client: "rdy"  ->  Server: "rdy"
            out.writeUTF("rdy");
            out.flush();
            if (!in.readUTF().equals("rdy")) {
                System.out.println("Server did not accept communication");
                return;
            }

            out.writeUTF(fileName);
            out.flush();
            String echoed = in.readUTF();
            if (!echoed.equals(fileName)) {
                System.out.println("Server sent back a different file name: " + echoed);
                sendClose(out);
                return;
            }

            // Asks for the amount of chunks
            out.writeUTF("chunks");
            out.flush();
            int numChunks = in.readInt();
            if (numChunks < 0) {
                System.out.println("Server says the file '" + fileName + "' does not exist");
                sendClose(out);
                return;
            }
            System.out.println("File will arrive in " + numChunks + " chunks");

            // Sends rdyD so the server can send the chunk
            out.writeUTF("rdyD");
            out.flush();

            byte[][] chunks = new byte[numChunks][];
            int received = 0;
            while (received < numChunks) {
                int index = in.readInt();
                int size = in.readInt();

                if (index < 0 || index >= numChunks) {
                    throw new IOException("Server sent invalid chunk index " + index);
                }
                if (chunks[index] != null) {
                    throw new IOException("Server sent chunk " + index + " twice");
                }
                if (size < 0 || size > MAX_CHUNK_BYTES) {
                    throw new IOException("Server sent invalid chunk size " + size);
                }

                byte[] data = new byte[size];
                in.readFully(data);
                chunks[index] = data;
                received++;
                System.out.println("Received chunk " + index + " (" + size + " bytes) [" + received + "/" + numChunks + "]");

                if (received < numChunks) {
                    out.writeUTF("next");
                    out.flush();
                }
            }

            // Combine the chunks and save them to disk
            Files.createDirectories(DOWNLOAD_DIR);
            Path target = DOWNLOAD_DIR.resolve(Paths.get(fileName).getFileName());
            long total = 0;
            try (OutputStream fileOut = Files.newOutputStream(target)) {
                for (byte[] chunk : chunks) {
                    fileOut.write(chunk);
                    total += chunk.length;
                }
            }
            System.out.println("Combined " + numChunks + " chunks (" + total + " bytes) into "
                    + target.toAbsolutePath());

            sendClose(out);
        }
    }

    private static void sendClose(DataOutputStream out) throws IOException {
        out.writeUTF("close");
        out.flush();
    }
}
