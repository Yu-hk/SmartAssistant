package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.memory.ProfileRecoveryGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Combined rollback-state contract. Real PG/Redis/MQ/POSIX, synthetic data only.
 * pg_dump fidelity is covered separately; this suite injects old rows/cache/files
 * and redelivered messages, without restoring production or replaying orders. */
@Tag("integration")
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
class ProfileCrossStoreRecoveryIntegrationTest {
    final ProfileControlArchiveIntegrationTest h=new ProfileControlArchiveIntegrationTest();
    final ProfileGenerationFenceIntegrationTest other=new ProfileGenerationFenceIntegrationTest();
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    final Set<String> keys=new HashSet<>();
    @org.junit.jupiter.api.io.TempDir Path files;
    @BeforeAll static void database() { ProfileControlArchiveIntegrationTest.database(); }
    @AfterAll static void close() { ProfileControlArchiveIntegrationTest.close(); }
    @BeforeEach void setup() throws Exception { h.setup();other.fixture(); }
    @AfterEach void cleanup() throws Exception {
        try { if(!keys.isEmpty()) ProfileRequestRedisIntegrationTest.redis.delete(keys);other.cleanup(); }
        finally { h.cleanup(); }
    }
    @Test void restoredDerivedCopiesAndRedeliveredReferencesConvergeWithoutRecreatingProfile() throws Exception {
        String port=System.getenv("CHAT_DISPATCH_TEST_RABBIT_PORT");
        assertNotNull(port,"Dedicated broker port required; missing setup is a failure");
        var broker=new com.rabbitmq.client.ConnectionFactory();
        broker.setHost(System.getenv().getOrDefault("CHAT_DISPATCH_TEST_RABBIT_HOST","127.0.0.1"));
        broker.setPort(Integer.parseInt(port));
        broker.setUsername(Objects.requireNonNull(System.getenv("CHAT_DISPATCH_TEST_RABBIT_USERNAME")));
        broker.setPassword(Objects.requireNonNull(System.getenv("CHAT_DISPATCH_TEST_RABBIT_PASSWORD")));
        broker.setHandshakeTimeout(30000);
        broker.setConnectionTimeout(10000);
        String queue="test.profile-restore."+UUID.randomUUID();
        var db=ProfileControlArchiveIntegrationTest.db();var redis=ProfileRequestRedisIntegrationTest.redis;
        long user=h.fixture.user;
        h.fixture.save(0);other.save(0);h.addLog("synthetic derived prompt");
        var candidates=new ProfileCommitCandidateStore(db,json,h.fixture.fence);
        var candidate=new UserProfileService.PreparedProfileCandidate(user,"restore-fixture",0,
            ProfileGenerationFenceIntegrationTest.report(),"synthetic private text",null,List.of(),0);
        var event=UserProfileCommitRequestedEvent.reference(candidate,candidates.stage(candidate));
        var projection=new ProfileRequestRedisStore(redis,h.fixture.fence);
        keys.addAll(ProfileRequestRedisStore.keys(user,"restore-fixture"));
        projection.publish(user,"restore-fixture",0,"READY:old","old-ref",true,Duration.ofMinutes(5));
        String otherKey="user_profile:"+other.user;keys.add(otherKey);redis.opsForValue().set(otherKey,"survivor");
        Path root=files.resolve("legacy-users"),owner=root.resolve(Long.toString(user)),survivor=root.resolve(Long.toString(other.user));
        Files.createDirectories(owner);Files.createDirectories(survivor);
        Path profile=owner.resolve("preferences.json"),chat=owner.resolve("chat.json"),kept=survivor.resolve("preferences.json");
        Files.writeString(profile,"synthetic old profile");Files.writeString(chat,"original chat");Files.writeString(kept,"survivor");
        ReflectionTestUtils.setField(h.service,"legacy",new ProfileLegacyFiles(root.toString(),true));
        try(var connection=broker.newConnection();var channel=connection.createChannel()) {
            channel.queueDeclare(queue,true,false,false,Map.of("x-expires",600000));
            try {
                channel.confirmSelect();byte[] payload=json.writeValueAsBytes(event);
                assertFalse(new String(payload,java.nio.charset.StandardCharsets.UTF_8).contains("synthetic private text"));
                channel.basicPublish("",queue,new com.rabbitmq.client.AMQP.BasicProperties.Builder().deliveryMode(2).build(),payload);
                channel.waitForConfirmsOrDie(5000);
                var unacked=ProfileCommitBrokerIntegrationTest.get(channel,queue);
                channel.basicNack(unacked.getEnvelope().getDeliveryTag(),false,true);
                UUID job=h.service.request(user,UUID.randomUUID());h.archive.sync();
                var guard=new ProfileRecoveryGuard(db,true,h.disk.root,h.disk.source);
                ReflectionTestUtils.setField(h.fixture.fence,"recoveryGuard",guard);
                // Inject old database lifecycle with the newer independent journal retained.
                db.update("UPDATE profile_lifecycle SET generation=0,analysis_enabled=true WHERE user_id=?",user);
                try {
                    assertThrows(ProfileRecoveryGuard.Unavailable.class,()->h.fixture.store.load(user));
                    assertThrows(ProfileRecoveryGuard.Unavailable.class,()->projection.publish(user,"late",0,"READY:late",null,true,Duration.ofMinutes(1)));
                } finally { db.update("UPDATE profile_lifecycle SET generation=1,analysis_enabled=false WHERE user_id=?",user); }
                // Unsafe/unknown restored files must block completion, not be silently erased.
                Path unknown=owner.resolve("unknown-memory.md");Files.writeString(unknown,"unclassified synthetic");
                assertTrue(h.service.runNext());
                assertEquals("RETRY",h.receipt(job,"LEGACY_STORAGE"));
                assertEquals("PARTIAL",h.service.status(user,job).get("state"));
                assertTrue(Files.exists(profile));
                var delivery=ProfileCommitBrokerIntegrationTest.get(channel,queue);
                assertTrue(delivery.getEnvelope().isRedeliver());
                var listener=new UserProfileCommitListener(json,new UserProfileService(null,h.fixture.store,null),candidates);
                var message=MessageBuilder.withBody(delivery.getBody()).build();
                listener.receive(message);listener.receive(message);
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
                assertEquals(0L,db.queryForObject("SELECT count(*) FROM user_profile_snapshot WHERE user_id=?",Long.class,user));
                assertEquals(0L,db.queryForObject("SELECT count(*) FROM profile_commit_candidate WHERE user_id=?",Long.class,user));
                Files.delete(unknown); // exact file created by this fixture only
                db.update("UPDATE profile_cleanup_receipt SET next_attempt_at=CURRENT_TIMESTAMP WHERE job_id=?",job);
                // Restart coordinator, preserving durable receipts and the independent archive.
                var restarted=h.coordinator(db,h.archive);
                ReflectionTestUtils.setField(restarted,"legacy",new ProfileLegacyFiles(root.toString(),true));
                assertTrue(restarted.runNext());
                assertEquals("ONLINE_CLEANED",restarted.status(user,job).get("state"));
                assertEquals(5L,db.queryForObject("SELECT count(*) FROM profile_cleanup_receipt WHERE job_id=? AND state='SUCCEEDED'",Long.class,job));
                assertFalse(Files.exists(profile));assertEquals("original chat",Files.readString(chat));assertEquals("survivor",Files.readString(kept));
                assertEquals("survivor",redis.opsForValue().get(otherKey));assertTrue(other.store.load(other.user).isPresent());
                assertEquals("1|PAUSED",redis.opsForValue().get(ProfileRequestRedisStore.barrierKey(user)));
                assertFalse(redis.hasKey(ProfileRequestRedisStore.keys(user,"restore-fixture").getFirst()));
                assertEquals("original question",db.queryForObject("SELECT user_input FROM routing_call_log WHERE user_id=?",String.class,user));
                assertNull(channel.basicGet(queue,false));
            } finally { channel.queueDelete(queue); }
        }
    }
}
