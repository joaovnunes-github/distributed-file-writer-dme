package src;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class FileWriter {

    private final FileAccessManager accessManager;

    public FileWriter(FileAccessManager accessManager) {
        this.accessManager = accessManager;
    }

    public void write() {
        accessManager.requestLock();
        while (!accessManager.hasLockBeenGranted()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        try {
            Path path = Paths.get("shared_file.txt");
            Files.write(path, "|".getBytes(), StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);

            Files.write(path, ".".getBytes(), StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);

            System.out.println("Process wrote |.");
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            accessManager.releaseLock();
        }
    }
}
