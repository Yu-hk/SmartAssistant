package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.routing.contract.RoutingKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.Duration;
import java.util.List;

/** Publication ordered with the PG lifecycle lock and persistent Redis generation barrier. */
public class ProfileRequestRedisStore {
    private final StringRedisTemplate redis;
    private final ProfileGenerationFence fence;
    /** Package-local transport constructor for isolated Redis tests only. */
    ProfileRequestRedisStore(StringRedisTemplate redis) { this(redis,null); }
    public ProfileRequestRedisStore(StringRedisTemplate redis,ProfileGenerationFence fence) {
        this.redis=redis; this.fence=fence;
    }

    static final DefaultRedisScript<Long> PUBLISH=new DefaultRedisScript<>("""
        local expected={'string','string','string','string','zset','string'}
        for i=1,6 do
          local t=redis.call('TYPE',KEYS[i]).ok
          if t~='none' and t~=expected[i] then return -2 end
        end
        local owner=redis.call('GET',KEYS[4])
        if owner and owner~=ARGV[1] then return -1 end
        -- Never adopt legacy, unowned content: wait for normal expiry instead.
        if not owner and redis.call('EXISTS',KEYS[1],KEYS[2],KEYS[3])>0 then return -1 end
        local barrier=redis.call('GET',KEYS[6])
        local generation=ARGV[7]
        if barrier then
          local old,status=string.match(barrier,'^(%d+)|(%u+)$')
          if not old or (status~='ACTIVE' and status~='PAUSED') then return -4 end
          -- Decimal strings preserve Java long values beyond Lua integer precision.
          if #old>#generation or (#old==#generation and old>generation) then return -4 end
          if old==generation and status~='ACTIVE' then return -4 end
        end
        if redis.call('EXISTS',KEYS[3])>0 then return 0 end
        local now=redis.call('TIME')
        local ms=tonumber(now[1])*1000+math.floor(tonumber(now[2])/1000)
        local ttl=tonumber(ARGV[5])
        -- Prune expired metadata only; never scan/delete unrelated keys.
        redis.call('ZREMRANGEBYSCORE',KEYS[5],'-inf',ms)
        if not redis.call('ZSCORE',KEYS[5],ARGV[6]) and redis.call('ZCARD',KEYS[5])>=500 then return -3 end
        -- Caller holds the authoritative PG lifecycle lock. Never expire control state.
        redis.call('SET',KEYS[6],generation..'|ACTIVE')
        local keep=math.max(ttl,redis.call('PTTL',KEYS[4]))
        redis.call('SET',KEYS[1],ARGV[2],'PX',ttl)
        if ARGV[3]~='' then redis.call('SET',KEYS[2],ARGV[3],'PX',ttl) end
        if ARGV[4]=='1' then redis.call('SET',KEYS[3],'DONE','PX',keep) end
        redis.call('SET',KEYS[4],ARGV[1],'PX',keep)
        local expiry=ms+keep
        local old=redis.call('ZSCORE',KEYS[5],ARGV[6])
        if old then expiry=math.max(expiry,tonumber(old)) end
        redis.call('ZADD',KEYS[5],expiry,ARGV[6])
        redis.call('PEXPIRE',KEYS[5],math.max(keep,redis.call('PTTL',KEYS[5])))
        return 1
        """,Long.class);
    static final DefaultRedisScript<Long> RETIRE=new DefaultRedisScript<>("""
        if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end
        if redis.call('GET',KEYS[2])~=ARGV[2] then return 0 end
        return redis.call('DEL',KEYS[2])
        """,Long.class);

    public void publish(Long userId,String requestId,long generation,String state,String candidate,boolean done,Duration ttl) {
        requireIdentity(userId,requestId,generation);
        long millis=ttl.toMillis();
        if(millis<1000 || millis>600000 || state==null) throw new IllegalArgumentException("Invalid profile publication");
        java.util.function.Supplier<Long> publication=()->{
            Long result=redis.execute(PUBLISH,keys(userId,requestId),userId+"|"+generation,state,
                    candidate==null?"":candidate,done?"1":"0",Long.toString(millis),requestId,Long.toString(generation));
            if(result==null || result<0) throw new IllegalStateException("Profile publication rejected");
            return result;
        };
        if(fence==null) publication.get(); // isolated transport tests
        else fence.write(userId,generation,publication);
    }

    public void retire(Long userId,String requestId,long generation,String serializedCandidate) {
        requireIdentity(userId,requestId,generation);
        redis.execute(RETIRE,List.of(ownerKey(requestId),RoutingKeys.userProfileCandidate(requestId)),
                userId+"|"+generation,serializedCandidate);
    }
    static List<String> keys(Long userId,String requestId) {
        return List.of(RoutingKeys.userProfileContext(requestId),RoutingKeys.userProfileCandidate(requestId),
                doneKey(requestId),ownerKey(requestId),indexKey(userId),barrierKey(userId));
    }
    static String barrierKey(Long userId) { return "routing:user-profile-lifecycle:"+userId; }
    static String indexKey(Long userId) { return "routing:user-profile-index:"+userId; }
    static String ownerKey(String requestId) { return "routing:user-profile-owner:"+requestId; }
    static String doneKey(String requestId) { return "routing:user-profile-done:"+requestId; }
    private static void requireIdentity(Long userId,String requestId,long generation) {
        if(userId==null || userId<=0 || generation<0 || requestId==null || requestId.isBlank() || requestId.length()>128)
            throw new IllegalArgumentException("Invalid profile request identity");
    }
}
