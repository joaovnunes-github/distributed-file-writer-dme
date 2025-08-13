package src;

public class Process {

    public static void main(String[] args) {
        try {
            FileAccessConfiguration config = new FileAccessConfiguration(args);
            FileAccessManager accessManager = new FileAccessManager(config);
            FileWriter writer = new FileWriter(accessManager);

            while (true) {
                writer.write();
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
