/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MinerUSidecarClient 集成测试：真实启动 stub sidecar 子进程，验证：
 * <ul>
 *   <li>stdio JSON 协议（请求 → 响应）；</li>
 *   <li>request_id 关联；</li>
 *   <li>进程池可正常归还。</li>
 * </ul>
 * 当运行环境无 Python3（或 stub 脚本不可用）时，按约定 skip，不阻塞构建。
 */
class MinerUSidecarClientIntegrationTest {

    private MinerUSidecarClient client;

    /** 探测可用的 python 解释器（python3 / python），无则返回 null */
    private static String detectPython() {
        for (String candidate : new String[]{"python3", "python"}) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").start();
                if (p.waitFor(10, TimeUnit.SECONDS)) {
                    return candidate;
                }
            } catch (Exception ignore) {
                // 忽略：尝试下一个候选
            }
        }
        return null;
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void shouldTalkToRealSidecarProcess() throws Exception {
        String python = detectPython();
        assumeTrue(python != null, "python3/python 不可用，跳过 sidecar 集成测试");

        Path stub = Paths.get("src/test/resources/mineru/mineru_sidecar_stub.py").toAbsolutePath();
        assumeTrue(new File(stub.toString()).exists(), "stub sidecar 脚本不存在，跳过");

        MinerUProperties props = new MinerUProperties();
        props.setEnabled(true);
        props.setSidecarCommand(python + " " + stub);
        props.setWarmInstances(1);
        props.setTimeoutMs(30000L);

        client = new MinerUSidecarClient(props);

        MinerUParseRequest req = new MinerUParseRequest();
        req.setPdf("/abs/sample.pdf");
        req.setPages("all");
        req.setRequestId("integration-1");
        req.setImagesDir(System.getProperty("java.io.tmpdir") + "/mineru/integration-1");

        MinerUParseResponse resp = client.parse(req);

        assertNotNull(resp);
        assertTrue(resp.isOk(), "sidecar 应返回 ok 状态");
        assertEquals("integration-1", resp.getRequestId());
        assertNotNull(resp.getPages());
        assertEquals(1, resp.getPages().size());
        assertEquals(1, resp.getPages().get(0).getPageNo());
        assertEquals(2, resp.getPages().get(0).getBlocks().size());
    }

    @Test
    void shouldThrowWhenCommandMissing() {
        // sidecar 命令不存在 → 预热失败 → 抛 DocumentParseException（上层据此回退）
        MinerUProperties props = new MinerUProperties();
        props.setEnabled(true);
        props.setSidecarCommand("this-command-does-not-exist-xyz");
        props.setWarmInstances(1);

        client = new MinerUSidecarClient(props);
        MinerUParseRequest req = new MinerUParseRequest();
        req.setPdf("/abs/x.pdf");
        req.setRequestId("missing");
        req.setImagesDir(System.getProperty("java.io.tmpdir"));

        try {
            client.parse(req);
            org.junit.jupiter.api.Assertions.fail("应因 sidecar 不可用而抛 DocumentParseException");
        } catch (DocumentParseException e) {
            // 预期：上层应据此回退 PDFBox
            assertTrue(e.getMessage().contains("sidecar"), "异常应说明 sidecar 预热失败: " + e.getMessage());
        }
    }
}
