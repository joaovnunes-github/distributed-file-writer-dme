package src;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class FileWriter {

    private final DistributedAccessManager accessManager;

    public FileWriter(DistributedAccessManager accessManager) {
        this.accessManager = accessManager;
    }

    public void write() throws InterruptedException {
        try {
            accessManager.requestLock().thenRunAsync(() -> {
                try {
                    System.out.println("Entering critical section.");
                    Path path = Paths.get("shared_file.txt");
                    Files.write(path, "|".getBytes(), StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    Files.write(path, ".".getBytes(), StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    System.out.println("Process wrote |.");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).join();
        } finally {
            accessManager.releaseLock();
            Thread.sleep(10);
        }
    }

    public void writeSnapshot(String json, int processID, int snapshotID) {
        writeSnapshotInternal(json, processID, snapshotID);
    }

    private void writeSnapshotInternal(String json, int processID, Integer snapshotID) {
        try {
            Path dir = Paths.get("snapshot");
            Files.createDirectories(dir);
            String fileName = "snapshot_" + processID + "_sid" + snapshotID + ".json";
            Path path = dir.resolve(fileName);
            Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
