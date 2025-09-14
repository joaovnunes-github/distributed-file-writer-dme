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

            if(config.getProcessID() == 1) {
                Thread t = new Thread(() -> {
                    while (true) {
                        try {
                            Thread.sleep(1000);
                            accessManager.initiateSnapshot();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
            }

            while (true) {
                writer.write();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
