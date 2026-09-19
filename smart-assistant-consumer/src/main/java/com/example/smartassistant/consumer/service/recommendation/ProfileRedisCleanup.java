package com.example.smartassistant.consumer.service.recommendation;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;

/** Called only while a cleanup job holds the disabled PG lifecycle row lock. */
@Service
public class ProfileRedisCleanup {
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCAN_BATCH=new DefaultRedisScript<>(
            "return redis.call('SCAN',ARGV[1],'MATCH',ARGV[2],'COUNT',200)",List.class);
    private final StringRedisTemplate redis;
    public ProfileRedisCleanup(StringRedisTemplate redis) { this.redis=redis; }
    static final DefaultRedisScript<Long> CLEAN=new DefaultRedisScript<>("""
        local t=redis.call('TYPE',KEYS[1]).ok
        if t~='none' and t~='string' then return -1 end
        local barrier=redis.call('GET',KEYS[1])
        if barrier then
          local old,state=string.match(barrier,'^(%d+)|(%u+)$')
          if not old or (state~='ACTIVE' and state~='PAUSED') then return -1 end
          if #old>#ARGV[2] or (#old==#ARGV[2] and old>ARGV[2]) then return -2 end
        end
        -- Retain the tombstone even if an inventory inconsistency prevents cleanup.
        redis.call('SET',KEYS[1],ARGV[2]..'|PAUSED')
        local indexType=redis.call('TYPE',KEYS[2]).ok
        local legacyType=redis.call('TYPE',KEYS[3]).ok
        local answerProfileType=redis.call('TYPE',KEYS[4]).ok
        if (indexType~='none' and indexType~='zset') or (legacyType~='none' and legacyType~='hash')
          or (answerProfileType~='none' and answerProfileType~='string') then return -3 end
        if redis.call('ZCARD',KEYS[2])>500 then return -3 end
        local requests=redis.call('ZRANGE',KEYS[2],0,-1)
        local remove={}
        for _,id in ipairs(requests) do
          if #id==0 or #id>128 then return -3 end
          local keys={'routing:user-profile-context:'..id,'routing:user-profile-candidate:'..id,
                      'routing:user-profile-done:'..id,'routing:user-profile-owner:'..id}
          for _,key in ipairs(keys) do
            local kind=redis.call('TYPE',key).ok
            if kind~='none' and kind~='string' then return -3 end
          end
          local owner=redis.call('GET',keys[4])
          if owner then
            local uid,gen=string.match(owner,'^(%d+)|(%d+)$')
            if uid~=ARGV[1] or not gen or #gen>#ARGV[2] or (#gen==#ARGV[2] and gen>ARGV[2]) then return -3 end
          elseif redis.call('EXISTS',keys[1],keys[2],keys[3])>0 then return -3 end
          for _,key in ipairs(keys) do table.insert(remove,key) end
        end
        for _,key in ipairs(remove) do redis.call('DEL',key) end
        redis.call('DEL',KEYS[2],KEYS[3],KEYS[4])
        return 1
        """,Long.class);
    public void clean(Long userId,long generation) {
        if(userId==null || userId<=0 || generation<=0) throw new IllegalArgumentException("Cleanup identity required");
        var derived=new java.util.LinkedHashSet<String>();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        for(String pattern:List.of("answer:"+userId+":*","user:memory:"+userId+":*",
                "consumer:semantic-answer:*:u"+userId+":*","router:product-node:*:u"+userId+":*")) {
            String cursor="0";
            do {
                // One explicit batch per call: library Cursor.hasNext() can hide an
                // unbounded number of empty MATCH batches on a large shared Redis.
                if(System.nanoTime()>deadline) throw new IllegalStateException("Derived cache inventory limit");
                List<?> batch=redis.execute(SCAN_BATCH,List.of(),cursor,pattern);
                if(batch==null || batch.size()!=2 || !(batch.get(0) instanceof String next)
                        || !next.matches("[0-9]+") || !(batch.get(1) instanceof List<?> keys))
                    throw new IllegalStateException("Invalid cache inventory response");
                cursor=next;
                for(Object key:keys) {
                    if(!(key instanceof String text)) throw new IllegalStateException("Invalid cache key");
                    derived.add(text);
                    if(derived.size()>500) throw new IllegalStateException("Derived cache inventory limit");
                }
            } while(!"0".equals(cursor));
        }
        Long result=redis.execute(CLEAN,List.of(ProfileRequestRedisStore.barrierKey(userId),
                ProfileRequestRedisStore.indexKey(userId),"user:profile:"+userId,"user_profile:"+userId),userId.toString(),Long.toString(generation));
        if(!Long.valueOf(1).equals(result)) throw new IllegalStateException("Redis cleanup inventory is incomplete");
        if(!derived.isEmpty()) redis.delete(derived);
    }
}
