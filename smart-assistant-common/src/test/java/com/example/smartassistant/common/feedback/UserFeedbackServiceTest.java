package com.example.smartassistant.common.feedback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserFeedbackServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void dislikeEntersStructuredReviewQueueButLikeDoesNot() throws Exception {
        Path users = tempDir.resolve("users");
        UserFeedbackService service = new UserFeedbackService(users.toString());

        service.recordFeedback("7", "s1", "问题", "错误回答", "dislike", "事实不对");

        Path queue = tempDir.resolve("feedback/review-queue.jsonl");
        assertTrue(Files.exists(queue));
        String content = Files.readString(queue);
        assertTrue(content.contains("PENDING_REVIEW"));
        assertTrue(content.contains("事实不对"));

        long before = Files.size(queue);
        service.recordFeedback("7", "s1", "问题", "正确回答", "like", "");
        assertFalse(Files.size(queue) > before);
    }
}
