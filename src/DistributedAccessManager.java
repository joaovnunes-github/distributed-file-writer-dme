package src;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Consumer;

public class DistributedAccessManager implements Consumer<String> {

    private final List<Peer> peers;

    private final int id;

    private final NetworkClient networkClient;

    private int time = 0;

    private final PriorityBlockingQueue<Request> requests = new PriorityBlockingQueue<>();

    private final Set<Integer> replies = new HashSet<>();

    private CompletableFuture<Void> pendingLockRequest;

    public DistributedAccessManager(DistributedAccessConfiguration config,
                                    NetworkClient networkClient) {
        this.networkClient = networkClient;
        this.peers = config.getPeers();
        this.id = config.getProcessID();
    }

    public CompletableFuture<Void> requestLock() {
        if(pendingLockRequest != null) {
            System.out.println("Tried to request lock with pending request");
            return null;
        }

        Request request = new Request(time, id);
        requests.add(request);

        System.out.println("Process " + id + " requesting lock at time " + time);

        for(Peer peer : peers) {
            networkClient.sendMessage(peer, "REQUEST " + time + " " + id);
        }

        pendingLockRequest = new CompletableFuture<>();
        return pendingLockRequest;
    }

    @Override
    public void accept(String message) {
        processMessage(message);
    }

    private void processMessage(String message) {
        String[] parts = message.split(" ");
        String type = parts[0];
        int senderTime = Integer.parseInt(parts[1]);
        int senderID = Integer.parseInt(parts[2]);

        System.out.println("Process " + id + " received " + type + " from " + senderID);

        time = Math.max(time, senderTime) + 1;

        switch (type) {
            case "REQUEST":
                requests.add(new Request(senderTime, senderID));

                peers.stream().filter(p -> p.id == senderID).findFirst().ifPresent(
                    sender -> networkClient.sendMessage(sender, "REPLY " + time + " " + id));
                return;

            case "REPLY":
                replies.add(senderID);
                System.out.println(
                    "Process " + id + " received reply from " + senderID + " (" + replies.size() + "/" + peers.size() + ")");
                if(shouldGrantLock()) {
                    if(pendingLockRequest != null && !pendingLockRequest.isDone()) {
                        pendingLockRequest.complete(null);
                    }
                }
                break;

            case "RELEASE":
                requests.removeIf(r -> r.processID == senderID);
                if(shouldGrantLock()) {
                    if(pendingLockRequest != null && !pendingLockRequest.isDone()) {
                        System.out.println("Process " + id + " lock granted");
                        replies.clear();
                        time++;
                        pendingLockRequest.complete(null);
                    }
                }
                break;
        }
    }

    private boolean shouldGrantLock() {
        Request nextRequestToBeProcessed = requests.peek();
        if(nextRequestToBeProcessed == null) {
            return false;
        }

        return isThisRequestMine(nextRequestToBeProcessed) && allPeersHaveReplied();
    }

    private boolean allPeersHaveReplied() {
        return replies.size() >= peers.size();
    }

    private boolean isThisRequestMine(Request nextRequestToBeProcessed) {
        return nextRequestToBeProcessed.processID == id;
    }

    public void releaseLock() {
        requests.removeIf(this::isThisRequestMine);

        time++;
        for(Peer peer : peers) {
            System.out.println("Process " + id + " sending RELEASE to " + peer.id);
            networkClient.sendMessage(peer, "RELEASE " + time + " " + id);
        }

        System.out.println("Process " + id + " released lock");

        replies.clear();
        pendingLockRequest = null;
    }
}
