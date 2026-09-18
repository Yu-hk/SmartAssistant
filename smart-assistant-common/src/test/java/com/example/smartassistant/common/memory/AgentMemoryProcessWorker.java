package com.example.smartassistant.common.memory;

import java.nio.file.Files;
import java.nio.file.Path;

/** Separate-JVM fixture; writes only under the test-owned temporary directory. */
public class AgentMemoryProcessWorker {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var service = new AgentMemoryService(root.toString());
        if (args[1].equals("hold")) {
            Path lockPath = Files.createDirectories(root.resolve("fixture")).resolve("product-memory.md.lock");
            try (var channel = java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                Files.createFile(root.resolve("hold.ready"));
                Thread.sleep(60000);
            }
            return;
        }
        if (args[1].equals("single")) {
            service.save("product", "fixture", "attempt", "must-not-overwrite");
            return;
        }
        String prefix = args[1];
        Files.createFile(root.resolve(prefix + ".ready"));
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!Files.exists(root.resolve("start"))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Start barrier timed out");
            Thread.sleep(10);
        }
        for (int i = 0; i < 40; i++) {
            int index = i;
            accepted(() -> service.trySave("product", "fixture", prefix + index, "value" + index));
            accepted(() -> service.tryDelete("product", "fixture", "old" + prefix + index));
        }
    }

    // Test driver only: best-effort lock timeout is not an accepted write. Never retry an
    // acknowledged operation, so the final assertions still catch lost updates/resurrected keys.
    private static void accepted(java.util.function.BooleanSupplier operation) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (operation.getAsBoolean()) return;
            System.out.println("REJECTED_UPDATE_RETRY_IN_FIXTURE");
            Thread.sleep(10);
        }
        throw new IllegalStateException("Update repeatedly rejected");
    }
}
