package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.speech.SpeechSynthesisService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/speech")
public class SpeechOutputController {
    private final SpeechSynthesisService service;
    public SpeechOutputController(SpeechSynthesisService service) {this.service=service;}
    @GetMapping("/output-capabilities")
    public Map<String,Object> capabilities(@RequestHeader(value="X-User-Id",required=false) Long user) {
        requireUser(user);return Map.of("enabled",service.available(),"maxCharacters",3000);
    }
    public record Request(String requestId) {}
    @PostMapping(value="/syntheses",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> speak(@RequestHeader(value="X-User-Id",required=false) Long user,@RequestBody Request request) {
        requireUser(user);
        var audio=service.synthesize(user,request.requestId());
        var result=ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
                .header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff");
        if(audio.characters()!=null) result.header("X-Speech-Characters",audio.characters().toString());
        return result.body(audio.bytes());
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> error(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"语音回复暂不可用":e.getReason()));
    }
    private void requireUser(Long user) {if(user==null||user<=0)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");}
}
