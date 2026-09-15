package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.speech.SpeechRecognitionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/speech")
public class SpeechController {
    private final SpeechRecognitionService service;
    public SpeechController(SpeechRecognitionService service) { this.service = service; }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        requireUser(userId);
        return Map.of("enabled", service.available(), "maxSeconds", SpeechRecognitionService.MAX_SECONDS);
    }

    @PostMapping(value = "/transcriptions", consumes = "audio/wav")
    public SpeechRecognitionService.Transcript transcribe(HttpServletRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) throws IOException {
        requireUser(userId);
        if (request.getContentLengthLong() > SpeechRecognitionService.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "录音不能超过 60 秒");
        }
        // Bounded read also covers chunked uploads without Content-Length; no multipart/temp files.
        byte[] audio = request.getInputStream().readNBytes(SpeechRecognitionService.MAX_BYTES + 1);
        return service.transcribe(userId, audio);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> speechError(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null
                ? "语音输入暂不可用" : e.getReason()));
    }

    private static void requireUser(Long id) {
        // Identity is injected by Gateway after JWT validation; Consumer must remain private.
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后使用语音输入");
    }
}
