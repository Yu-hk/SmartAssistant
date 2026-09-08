package com.example.smartassistant.common.rag.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Immutable source boundary derived from the original request, never a planner summary. */
public record UserDocumentContext(boolean userOnly, String question, String evidence, String fingerprint) {
    private static final Pattern BLOCK = Pattern.compile(
            "(?s)```[^\\n]*\\n(.*?)```|“([^”]*)”|\"([^\"]*)\"|【资料】(.*?)【问题】");
    private static final Pattern ONLY = Pattern.compile(
            "(?:仅|只)(?:能)?(?:依据|根据|基于|使用|按|参考).{0,16}(?:资料|文档|材料|原文|文本)|"
            + "(?:资料|文档|材料|原文|文本).{0,8}(?:之外|以外).{0,8}(?:不要|不得|不能)|"
            + "(?i:only (?:use|based on).{0,30}(?:text|document|material))");

    public static UserDocumentContext from(String original) {
        String question = original == null ? "" : original;
        var matcher = BLOCK.matcher(question);
        StringBuilder evidence = new StringBuilder();
        StringBuffer instructions = new StringBuffer();
        while (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                if (matcher.group(i) != null && !matcher.group(i).isBlank()) {
                    evidence.append(matcher.group(i).strip()).append('\n');
                }
            }
            // Quoted content must not select permissions or override the source boundary.
            matcher.appendReplacement(instructions, "[用户资料]");
        }
        matcher.appendTail(instructions);
        boolean only = ONLY.matcher(instructions).find();
        String source = evidence.toString().strip();
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("USER_ONLY:v1\n" + source).getBytes(StandardCharsets.UTF_8)));
            return new UserDocumentContext(only, instructions.toString(), source, fingerprint);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
