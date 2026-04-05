package io.jvmdoctor.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class LastDumpSessionStore {

    private final Path storageFile;

    public LastDumpSessionStore() {
        this(Path.of(System.getProperty("user.home"), ".jvm-doctor", "last-dump-path.txt"));
    }

    public LastDumpSessionStore(Path storageFile) {
        this.storageFile = Objects.requireNonNull(storageFile, "storageFile");
    }

    public void save(String sourceFilePath) {
        if (sourceFilePath == null || sourceFilePath.isBlank()) {
            clear();
            return;
        }
        try {
            Path parent = storageFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(storageFile, sourceFilePath);
        } catch (IOException ignored) {
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(storageFile);
        } catch (IOException ignored) {
        }
    }

    public String load() {
        try {
            if (!Files.isRegularFile(storageFile)) {
                return null;
            }
            String savedPath = Files.readString(storageFile).trim();
            return savedPath.isBlank() ? null : savedPath;
        } catch (IOException ignored) {
            return null;
        }
    }

    Path storageFile() {
        return storageFile;
    }
}
