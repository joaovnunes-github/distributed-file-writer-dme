package src;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Consumer;

public class DistributedAccessManager implements Consumer<String> {

    private final LinkedBlockingQueue<Runnable> actionQueue = new LinkedBlockingQueue<>();

    private final NetworkClient networkClient;

    private final List<Peer> peers;

    private final int ID;

    private int time = 0;

    private final PriorityBlockingQueue<Request> requests = new PriorityBlockingQueue<>();

    private final Set<Integer> replies = new HashSet<>();

    private CompletableFuture<Void> pendingLockRequest;

    private boolean isInCriticalSection = false;

    // Snapshot related fields
    private boolean isSnapshotInProgress = false;

    private int snapshotInitiator = -1;

    private final Map<Integer, List<String>> channelState = new HashMap<>();

    private final Set<Integer> channelsPendingSnapshot = new HashSet<>();

    private String localSnapshotJson = null;

    private final Map<Integer, String> collectedSnapshots = new HashMap<>();

    private int currentSnapshotID = -1;

    private int snapshotLocalTime;

    private List<Request> snapshotRequests;

    private Set<Integer> snapshotReplies;

    private boolean snapshotIsInCritical;

    private boolean snapshotWantsCritical;

    public DistributedAccessManager(DistributedAccessConfiguration config,
                                    NetworkClient networkClient) {
        this.networkClient = networkClient;
        this.peers = config.getPeers();
        this.ID = config.getProcessID();

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

            Request request = new Request(time, ID);
            requests.add(request);

            for(Peer peer : peers) {
                networkClient.sendMessage(peer, "REQUEST " + time + " " + ID);
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

        if(isSnapshotInProgress && channelsPendingSnapshot.contains(
            senderID) && !"TAKE_SNAPSHOT".equals(type) && !"SNAPSHOT_DATA".equals(type)) {
            channelState.computeIfAbsent(senderID, k -> new ArrayList<>()).add(message);
        }
        switch (type) {
            case "REQUEST":
                requests.add(new Request(senderTime, senderID));
                var requestWithLowestTime = requests.peek();
                if(isInCriticalSection || isThisRequestMine(requestWithLowestTime)) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                }

                requests.removeIf((request -> request.processID == senderID));
                peers.stream().filter(p -> p.id == senderID).findFirst().ifPresent(
                    sender -> networkClient.sendMessage(sender, "REPLY " + time + " " + ID));
                break;

            case "REPLY":
                replies.add(senderID);

                if(shouldGrantLock()) {
                    isInCriticalSection = true;
                    pendingLockRequest.complete(null);
                }
                break;

            case "TAKE_SNAPSHOT":
                int snapshotID = Integer.parseInt(parts[3]);
                if(!isSnapshotInProgress) {
                    currentSnapshotID = snapshotID;
                    startRecording(senderID);

                    time++;
                    for(Peer peer : peers) {
                        networkClient.sendMessage(peer,
                                                  "TAKE_SNAPSHOT " + time + " " + ID + " " + currentSnapshotID);
                    }
                    channelsPendingSnapshot.remove(senderID);
                    break;
                }

                channelsPendingSnapshot.remove(senderID);
                if(channelsPendingSnapshot.isEmpty() && snapshotInitiator != ID) {
                    localSnapshotJson = buildCompletedSnapshotJson();
                    sendLocalSnapshotToInitiator();
                    isSnapshotInProgress = false;
                }
                break;

            case "SNAPSHOT_DATA":
                String snapshotData = new String(Base64.getDecoder().decode(parts[4]));
                collectedSnapshots.put(senderID, snapshotData);

                if(collectedSnapshots.size() == peers.size()) {
                    localSnapshotJson = buildCompletedSnapshotJson();
                    collectedSnapshots.put(ID, localSnapshotJson);
                    writeSnapshot();
                    isSnapshotInProgress = false;
                }
                break;
        }
    }

    private void startRecording(int initiator) {
        isSnapshotInProgress = true;
        snapshotInitiator = initiator;
        channelsPendingSnapshot.clear();
        channelState.clear();
        for(Peer p : peers) {
            channelsPendingSnapshot.add(p.id);
            channelState.put(p.id, new ArrayList<>());
        }

        snapshotLocalTime = time;
        snapshotRequests = new ArrayList<>(requests);
        snapshotReplies = new HashSet<>(replies);
        snapshotIsInCritical = isInCriticalSection;
        snapshotWantsCritical = pendingLockRequest != null && !pendingLockRequest.isDone();
        collectedSnapshots.clear();
    }

    private void sendLocalSnapshotToInitiator() {
        String encoded = Base64.getEncoder().encodeToString(localSnapshotJson.getBytes());
        time++;
        peers.stream().filter(p -> p.id == snapshotInitiator).findFirst().ifPresent(
            peer -> networkClient.sendMessage(peer,
                                              "SNAPSHOT_DATA " + time + " " + ID + " " + currentSnapshotID + " " + encoded));
    }

    private void writeSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"snapshot\":{");
        int i = 0;
        for(Map.Entry<Integer, String> e : collectedSnapshots.entrySet()) {
            if(i++ > 0) {sb.append(",");}
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
        }
        sb.append("}");
        sb.append("}");
        new FileWriter(this).writeSnapshot(sb.toString(), ID, currentSnapshotID);
    }

    private String buildCompletedSnapshotJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"time\":").append(snapshotLocalTime).append(",");
        sb.append("\"isInCriticalSection\":").append(snapshotIsInCritical).append(",");
        sb.append("\"wantsCriticalSection\":").append(snapshotWantsCritical).append(",");
        sb.append("\"requestsThisProcessMustReplyTo\":[");
        snapshotRequests.removeIf(req -> req.processID == ID);
        for(int i = 0; i < snapshotRequests.size(); i++) {
            Request r = snapshotRequests.get(i);
            if(i > 0) {sb.append(",");}
            sb.append("{\"time\":").append(r.timestamp).append(",\"processID\":")
              .append(r.processID).append("}");
        }
        sb.append("],");
        sb.append("\"repliesThisProcessReceived\":[");
        int j = 0;
        for(Integer r : snapshotReplies) {
            if(j++ > 0) {sb.append(",");}
            sb.append(r);
        }
        sb.append("],");
        sb.append("\"messagesThatWereInTransit\":{");
        int k = 0;
        for(Map.Entry<Integer, List<String>> e : channelState.entrySet()) {
            if(k++ > 0) {sb.append(",");}
            sb.append("\"").append(e.getKey()).append("\":[");
            List<String> msgs = e.getValue();
            for(int m = 0; m < msgs.size(); m++) {
                if(m > 0) {sb.append(",");}
                sb.append("\"").append(escape(msgs.get(m))).append("\"");
            }
            sb.append("]");
        }
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean shouldGrantLock() {
        Request nextRequestToBeProcessed = requests.peek();
        if(nextRequestToBeProcessed == null) {
            return false;
        }

        return isThisRequestMine(nextRequestToBeProcessed) && allPeersHaveReplied();
    }

    private boolean isThisRequestMine(Request nextRequestToBeProcessed) {
        return nextRequestToBeProcessed.processID == ID;
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
                    peer -> networkClient.sendMessage(peer, "REPLY " + time + " " + ID));
            }
        });
    }

    public void initiateSnapshot() {
        if(isSnapshotInProgress) {
            return;
        }

        actionQueue.offer(() -> {
            currentSnapshotID++;
            startRecording(ID);

            time++;
            for(Peer peer : peers) {
                networkClient.sendMessage(peer,
                                          "TAKE_SNAPSHOT " + time + " " + ID + " " + currentSnapshotID);
            }
        });
    }

}
