package src;

public class Process {

    public static void main(String[] args) {
        try {
            DistributedAccessConfiguration config = new DistributedAccessConfiguration(args);
            NetworkClient networkClient = new NetworkClient(config);
            DistributedAccessManager accessManager = new DistributedAccessManager(config,
                                                                                  networkClient);
            networkClient.addMessageListener(accessManager);
            FileWriter writer = new FileWriter(accessManager);

            while (true) {
                writer.write();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
