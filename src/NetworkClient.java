package src;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class NetworkClient {

    private ServerSocket serverSocket;

    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    private final int id;

    private final List<Peer> peers;

    public NetworkClient(DistributedAccessConfiguration config) throws IOException {
        this.id = config.getProcessID();
        this.peers = config.getPeers();
        this.startServer();
        this.waitForPeers();
        this.startListening();
    }

    public void startServer() throws IOException {
        int port = 8000 + id;
        serverSocket = new ServerSocket(port);
        System.out.println("Process " + id + " started on port " + port);
    }

    public void waitForPeers() {
        if(peers.isEmpty()) {return;}

        System.out.println("Waiting for peers to become available...");
        boolean allPeersReady = false;
        while (!allPeersReady) {
            allPeersReady = true;
            for(Peer peer : peers) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(peer.host, peer.port), 1000);
                } catch (IOException e) {
                    allPeersReady = false;
                    break;
                }
            }
            if(!allPeersReady) {
                try {
                    System.out.println("Not all peers ready, waiting...");
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
        System.out.println("All peers are ready!");
    }

    private void startListening() {
        Thread t = new Thread(() -> {
            while (true) {
                try (Socket clientSocket = serverSocket.accept();
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream()))) {
                    try {
                        String message = reader.readLine();
                        if(message != null) {
                            dispatch(message);
                        }
                    } catch (IOException ignored) {
                    }
                } catch (IOException ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void dispatch(String message) {
        for(Consumer<String> l : listeners) {
            try {
                l.accept(message);
            } catch (Exception ignored) {
            }
        }
    }

    public void sendMessage(Peer peer, String message) {
        try (Socket socket = new Socket(peer.host, peer.port);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            writer.println(message);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    public void addMessageListener(Consumer<String> listener) {
        listeners.add(listener);
    }
}
