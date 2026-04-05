package io.jvmdoctor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LastDumpSessionStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTripsStoredPath() {
        LastDumpSessionStore store = new LastDumpSessionStore(tempDir.resolve("last-dump-path.txt"));

        store.save("/tmp/example.dump");

        assertEquals("/tmp/example.dump", store.load());
    }

    @Test
    void clearRemovesStoredPath() {
        LastDumpSessionStore store = new LastDumpSessionStore(tempDir.resolve("last-dump-path.txt"));
        store.save("/tmp/example.dump");

        store.clear();

        assertNull(store.load());
    }
}
