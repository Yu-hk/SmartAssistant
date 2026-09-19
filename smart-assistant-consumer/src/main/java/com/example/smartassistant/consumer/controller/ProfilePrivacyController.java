package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.recommendation.ProfileCleanupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Owner is independently verified by ProfilePrivacyAuthentication, never by a caller header. */
@RestController
@RequestMapping("/api/privacy/profile")
public class ProfilePrivacyController {
    private final ProfileCleanupService service;
    public ProfilePrivacyController(ProfileCleanupService service){this.service=service;}
    public record DeleteRequest(UUID idempotencyKey,String confirmation) { }
    @GetMapping
    public ResponseEntity<?> overview(@RequestAttribute(value="profileAuthenticatedOwner",required=false) Long user) {
        if(user==null || user<=0) return unauthorized();
        try { return ResponseEntity.ok(service.overview(user)); }
        catch(RuntimeException unavailable){return unavailable();}
    }
    @PostMapping("/deletions")
    public ResponseEntity<?> delete(@RequestAttribute(value="profileAuthenticatedOwner",required=false) Long user,@RequestBody DeleteRequest request) {
        if(user==null || user<=0) return unauthorized();
        if(request==null || request.idempotencyKey()==null || !"DELETE_PROFILE_PAUSE_ANALYSIS".equals(request.confirmation()))
            return ResponseEntity.badRequest().body(Map.of("message","请确认清除画像并暂停后续分析。"));
        try {
            if(!service.operational()) return unavailable();
            return ResponseEntity.accepted().body(Map.of("jobId",service.request(user,request.idempotencyKey()).toString()));
        } catch(RuntimeException unavailable){return unavailable();}
    }
    @GetMapping("/deletions/{job}")
    public ResponseEntity<?> status(@RequestAttribute(value="profileAuthenticatedOwner",required=false) Long user,@PathVariable UUID job) {
        if(user==null || user<=0) return unauthorized();
        try {
            var result=service.status(user,job);
            return result.isEmpty()?ResponseEntity.notFound().build():ResponseEntity.ok(result);
        } catch(RuntimeException unavailable){return unavailable();}
    }
    private static ResponseEntity<?> unauthorized(){return ResponseEntity.status(401).body(Map.of("message","请先登录。"));}
    private static ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("message","画像清理暂不可用，请稍后查看处理状态。您的正常咨询不受影响。"));}
}
