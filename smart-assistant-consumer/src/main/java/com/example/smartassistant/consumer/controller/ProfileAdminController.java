package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.AdminProfileService;
import com.example.smartassistant.consumer.service.recommendation.ProfileCleanupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

/** Cross-user portrait metadata and audited administrator cleanup. */
@RestController
@RequestMapping("/api/admin/profiles")
public class ProfileAdminController {
    private final AdminProfileService profiles;
    private final ProfileCleanupService cleanup;
    public ProfileAdminController(AdminProfileService profiles,ProfileCleanupService cleanup){
        this.profiles=profiles;this.cleanup=cleanup;
    }
    public record CleanupRequest(UUID idempotencyKey,String expectedUsername,String reasonCode,String confirmation) { }

    @GetMapping
    public ResponseEntity<?> list(@RequestAttribute(value="profileAdminActor",required=false) Long actor,
                                  @RequestParam(required=false) String query,
                                  @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        if(actor==null) return forbidden();
        try { return ResponseEntity.ok(profiles.list(query,page,size)); }
        catch(RuntimeException error){return unavailable();}
    }
    @GetMapping("/{userId}")
    public ResponseEntity<?> detail(@RequestAttribute(value="profileAdminActor",required=false) Long actor,
                                    @PathVariable long userId) {
        if(actor==null) return forbidden();
        try {
            var result=profiles.detail(userId);
            return result.isEmpty()?ResponseEntity.notFound().build():ResponseEntity.ok(result);
        } catch(RuntimeException error){return unavailable();}
    }
    @PostMapping("/{userId}/deletions")
    public ResponseEntity<?> cleanup(@RequestAttribute(value="profileAdminActor",required=false) Long actor,
                                     @PathVariable long userId,@RequestBody CleanupRequest request) {
        if(actor==null) return forbidden();
        if(request==null || request.idempotencyKey()==null
                || !"ADMIN_DELETE_PROFILE_PAUSE_ANALYSIS".equals(request.confirmation()))
            return ResponseEntity.badRequest().body(Map.of("message","请确认代用户清除画像并暂停分析。"));
        try {
            if(!cleanup.operational()) return unavailable();
            UUID job=cleanup.requestForAdmin(actor,userId,request.expectedUsername(),request.reasonCode(),request.idempotencyKey());
            return ResponseEntity.accepted().body(Map.of("jobId",job.toString()));
        } catch(IllegalArgumentException conflict){return ResponseEntity.status(409).body(Map.of("message",conflict.getMessage()));}
          catch(SecurityException forbidden){return forbidden();}
          catch(RuntimeException error){return unavailable();}
    }
    private static ResponseEntity<?> forbidden(){return ResponseEntity.status(403).body(Map.of("message","需要管理员权限。"));}
    private static ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("message","画像管理暂不可用，请稍后重试。"));}
}
