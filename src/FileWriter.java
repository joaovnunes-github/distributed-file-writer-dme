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

    public void write() {
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
        }
    }
}
