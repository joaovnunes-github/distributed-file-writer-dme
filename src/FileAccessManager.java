package src;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

public class FileAccessManager {

    private final FileAccessConfiguration config;

    private int time;

    private final PriorityQueue<Request> requests;

    private final Set<Integer> replies;

    private ServerSocket serverSocket;

    private boolean isLockRequestPending;

    private static class Request implements Comparable<Request> {

        int timestamp;

        int processID;

        Request(int timestamp, int processID) {
            this.timestamp = timestamp;
            this.processID = processID;
        }

        @Override
        public int compareTo(Request other) {
            if(this.timestamp != other.timestamp) {
                return this.timestamp - other.timestamp;
            }
            return this.processID - other.processID;
        }

        @Override
        public boolean equals(Object obj) {
            if(this == obj) {return true;}
            if(obj == null || getClass() != obj.getClass()) {return false;}
            Request request = (Request)obj;
            return timestamp == request.timestamp && processID == request.processID;
        }

        @Override
        public int hashCode() {
            return timestamp * 31 + processID;
        }
    }

    public FileAccessManager(FileAccessConfiguration config) {
        this.config = config;
        this.time = 0;
        this.requests = new PriorityQueue<>();
        this.replies = new HashSet<>();
        this.isLockRequestPending = false;

        if(config.shouldApplyDME()) {
            try {
                startServer();
                waitForPeers();
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    private void startServer() throws IOException {
        int port = 8000 + config.getProcessID();
        serverSocket = new ServerSocket(port);
        System.out.println("Process " + config.getProcessID() + " started on port " + port);
    }

    private void waitForPeers() {
        if(config.getPeers().isEmpty()) {return;}

        System.out.println("Waiting for peers to become available...");
        boolean allPeersReady = false;
        while (!allPeersReady) {
            allPeersReady = true;
            for(FileAccessConfiguration.Peer peer : config.getPeers()) {
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

    public void requestLock() {
        if(!config.shouldApplyDME() || isLockRequestPending) {
            return;
        }

        Request request = new Request(time, config.getProcessID());
        requests.add(request);

        isLockRequestPending = true;

        System.out.println("Process " + config.getProcessID() + " requesting lock at time " + time);

        for(FileAccessConfiguration.Peer peer : config.getPeers()) {
            sendMessage(peer, "REQUEST " + time + " " + config.getProcessID());
        }
    }

    public boolean hasLockBeenGranted() {
        if(!config.shouldApplyDME()) {
            return true;
        }

        if(requests.isEmpty()) {
            return false;
        }

        receiveMessages();

        Request nextRequestToBeProcessed = requests.peek();
        if(isThisRequestMine(nextRequestToBeProcessed) && allPeersHaveReplied()) {
            System.out.println("Process " + config.getProcessID() + " lock granted");
            replies.clear();
            time++;
            return true;
        }
        return false;
    }

    private boolean allPeersHaveReplied() {
        return replies.size() >= config.getPeers().size();
    }

    private boolean isThisRequestMine(Request nextRequestToBeProcessed) {
        return nextRequestToBeProcessed.processID == config.getProcessID();
    }

    public void releaseLock() {
        if(!config.shouldApplyDME()) {
            return;
        }

        requests.removeIf(this::isThisRequestMine);

        time++;
        for(FileAccessConfiguration.Peer peer : config.getPeers()) {
            sendMessage(peer, "RELEASE " + time + " " + config.getProcessID());
        }

        System.out.println("Process " + config.getProcessID() + " released lock");

        replies.clear();
        isLockRequestPending = false;
    }

    private void receiveMessages() {
        if(!config.shouldApplyDME()) {return;}

        try {
            serverSocket.setSoTimeout(1);
            Socket clientSocket = serverSocket.accept();

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream()))) {
                String message = reader.readLine();
                if(message != null) {
                    processMessage(message);
                }
            }
            clientSocket.close();
        } catch (SocketTimeoutException e) {
            // No message received, continue
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    private void processMessage(String message) {
        String[] parts = message.split(" ");
        String type = parts[0];
        int senderTime = Integer.parseInt(parts[1]);
        int senderID = Integer.parseInt(parts[2]);

        System.out.println(
            "Process " + config.getProcessID() + " received " + type + " from " + senderID);

        time = Math.max(time, senderTime) + 1;

        switch (type) {
            case "REQUEST":
                requests.add(new Request(senderTime, senderID));

                FileAccessConfiguration.Peer sender = config.getPeers().stream()
                                                            .filter(p -> p.id == senderID)
                                                            .findFirst().orElse(null);
                if(sender != null) {
                    sendMessage(sender, "REPLY " + time + " " + config.getProcessID());
                }
                break;

            case "REPLY":
                replies.add(senderID);
                System.out.println(
                    "Process " + config.getProcessID() + " received reply from " + senderID + " (" + replies.size() + "/" + config.getPeers()
                                                                                                                                  .size() + ")");
                break;

            case "RELEASE":
                requests.removeIf(r -> r.processID == senderID);
                break;
        }
    }

    private void sendMessage(FileAccessConfiguration.Peer peer, String message) {
        try (Socket socket = new Socket(peer.host, peer.port);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            writer.println(message);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }
}
