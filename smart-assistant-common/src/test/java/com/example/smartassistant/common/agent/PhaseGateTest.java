package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseGateTest {

    @TempDir
    Path workspace;

    @Test
    void verifiesRelativeFilesAndGlobCounts() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/a.md"), "a");
        Files.writeString(workspace.resolve("docs/b.md"), "b");

        PhaseGate gate = new PhaseGate();
        var result = gate.verify(List.of(
                new PhaseGate.Check("file", PhaseGate.Check.TYPE_FILE_EXISTS,
                        "docs/a.md", "文档存在"),
                new PhaseGate.Check("count", PhaseGate.Check.TYPE_FILE_GLOB_COUNT,
                        "**/*.md 2", "文档数量")), List.of(), workspace.toString());

        assertTrue(result.allPassed());
    }

    @Test
    void userConfirmationMustBeExplicitlyProvided() {
        PhaseGate gate = new PhaseGate();
        var check = new PhaseGate.Check("approve", PhaseGate.Check.TYPE_USER_CONFIRMATION,
                "", "用户批准");

        assertFalse(gate.verify(List.of(check), List.of(), workspace.toString()).allPassed());
        assertTrue(gate.verify(List.of(check), List.of(), workspace.toString(),
                List.of("approve")).allPassed());
    }
}
