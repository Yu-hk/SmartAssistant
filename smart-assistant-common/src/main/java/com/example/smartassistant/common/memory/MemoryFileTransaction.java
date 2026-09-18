package com.example.smartassistant.common.memory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/** Local-filesystem cooperative transaction. Keep the sidecar inode stable across replacements. */
final class MemoryFileTransaction {
    static final int MAX_BYTES = 262144;
    static final long LOCK_WAIT_MILLIS = 250;
    @FunctionalInterface interface Action { void run() throws IOException; }

    private MemoryFileTransaction() { }

    static void checkPath(Path file) throws IOException {
        for (Path current = file; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic memory paths are not allowed");
        }
    }

    static void mutate(Path file, Action action) throws IOException, InterruptedException {
        checkPath(file);
        Files.createDirectories(file.getParent());
        Path sidecar = file.resolveSibling(file.getFileName() + ".lock");
        checkPath(sidecar);
        // Never delete the sidecar: replacing it could let two processes lock different inodes.
        try (FileChannel channel = FileChannel.open(sidecar, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOCK_WAIT_MILLIS);
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Memory write interrupted");
                FileLock lock = null;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException busy) { /* Another service instance in this JVM. */ }
                if (lock != null) {
                    try (FileLock held = lock) {
                        checkPath(file);
                        action.run();
                        return;
                    }
                }
                if (System.nanoTime() >= deadline) throw new IOException("Memory lock wait exceeded");
                Thread.sleep(10);
            }
        }
    }

    static void replace(Path file, byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Memory output exceeds safe size");
        checkPath(file);
        Path temporary = Files.createTempFile(file.getParent(), ".memory-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            checkPath(file);
            // Unsupported atomic moves fail closed: never truncate the original as a fallback.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
