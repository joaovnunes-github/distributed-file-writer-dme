package src;

import java.util.ArrayList;
import java.util.List;

public class FileAccessConfiguration {

    private final int processID;

    private final boolean shouldApplyDME;

    private final List<Peer> peers;

    public static class Peer {

        public final int id;

        public final String host;

        public final int port;

        public Peer(int id, String host, int port) {
            this.id = id;
            this.host = host;
            this.port = port;
        }
    }

    public FileAccessConfiguration(String[] args) {
        if(args.length < 2) {
            throw new IllegalArgumentException(
                "Usage: java Process <processID> <useDME> [peer1_id:host:port] [peer2_id:host:port] ...");
        }

        this.processID = Integer.parseInt(args[0]);
        this.shouldApplyDME = Boolean.parseBoolean(args[1]);
        this.peers = new ArrayList<>();

        for(int i = 2; i < args.length; i++) {
            String[] peerInfo = args[i].split(":");

            int peerID = Integer.parseInt(peerInfo[0]);
            String host = peerInfo[1];
            int port = Integer.parseInt(peerInfo[2]);

            peers.add(new Peer(peerID, host, port));
        }
    }

    public int getProcessID() {
        return processID;
    }

    public boolean shouldApplyDME() {
        return shouldApplyDME;
    }

    public List<Peer> getPeers() {
        return peers;
    }
}
