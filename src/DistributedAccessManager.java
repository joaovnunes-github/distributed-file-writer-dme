package src;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Consumer;

public class DistributedAccessManager implements Consumer<String> {

    private final LinkedBlockingQueue<Runnable> actionQueue = new LinkedBlockingQueue<>();

    private final NetworkClient networkClient;

    private final List<Peer> peers;

    private final int id;

    private int time = 0;

    private final PriorityBlockingQueue<Request> requests = new PriorityBlockingQueue<>();

    private final Set<Integer> replies = new HashSet<>();

    private CompletableFuture<Void> pendingLockRequest;

    private boolean isInCriticalSection = false;

    public DistributedAccessManager(DistributedAccessConfiguration config,
                                    NetworkClient networkClient) {
        this.networkClient = networkClient;
        this.peers = config.getPeers();
        this.id = config.getProcessID();

        Thread workerThread = new Thread(() -> {
            try {
                while (true) {
                    Runnable action = actionQueue.take();
                    action.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public CompletableFuture<Void> requestLock() {
        var future = new CompletableFuture<Void>();

        actionQueue.offer(() -> {
            time++;

            Request request = new Request(time, id);
            requests.add(request);

            for(Peer peer : peers) {
                networkClient.sendMessage(peer, "REQUEST " + time + " " + id);
            }

            pendingLockRequest = future;
        });

        return future;
    }

    @Override
    public void accept(String message) {
        actionQueue.offer(() -> processMessage(message));
    }

    private void processMessage(String message) {
        String[] parts = message.split(" ");
        String type = parts[0];
        int senderTime = Integer.parseInt(parts[1]);
        int senderID = Integer.parseInt(parts[2]);

        time = Math.max(time, senderTime) + 1;

        switch (type) {
            case "REQUEST":
                requests.add(new Request(senderTime, senderID));

                var requestWithLowestTime = requests.peek();
                if(isInCriticalSection || (requestWithLowestTime != null && isThisRequestMine(
                    requestWithLowestTime))) {
                    return;
                }

                requests.removeIf((request -> request.processID == senderID));
                peers.stream().filter(p -> p.id == senderID).findFirst().ifPresent(
                    sender -> networkClient.sendMessage(sender, "REPLY " + time + " " + id));
                return;

            case "REPLY":
                replies.add(senderID);

                if(shouldGrantLock()) {
                    isInCriticalSection = true;
                    pendingLockRequest.complete(null);
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

    private boolean isThisRequestMine(Request nextRequestToBeProcessed) {
        return nextRequestToBeProcessed.processID == id;
    }

    private boolean allPeersHaveReplied() {
        return replies.size() >= peers.size();
    }

    public void releaseLock() {
        actionQueue.offer(() -> {
            requests.removeIf(this::isThisRequestMine);
            replies.clear();
            isInCriticalSection = false;
            pendingLockRequest = null;
            time++;

            for(Request request : requests.toArray(new Request[0])) {
                requests.remove(request);
                peers.stream().filter((p) -> p.id == request.processID).findFirst().ifPresent(
                    peer -> networkClient.sendMessage(peer, "REPLY " + time + " " + id));
            }
        });
    }
}
