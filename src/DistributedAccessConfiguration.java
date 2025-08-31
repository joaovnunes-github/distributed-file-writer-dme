package src;

import java.util.ArrayList;
import java.util.List;

public class DistributedAccessConfiguration {

    private final int processID;

    private final List<Peer> peers;

    public DistributedAccessConfiguration(String[] args) {
        if(args.length < 2) {
            throw new IllegalArgumentException(
                "Usage: java Process <processID> <useDME> [peer1_id:host:port] [peer2_id:host:port] ...");
        }

        this.processID = Integer.parseInt(args[0]);
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

    public List<Peer> getPeers() {
        return peers;
    }
}
