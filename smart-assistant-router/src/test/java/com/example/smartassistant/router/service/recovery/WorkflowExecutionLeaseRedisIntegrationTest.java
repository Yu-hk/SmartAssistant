package com.example.smartassistant.router.service.recovery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "ROUTER_RECOVERY_REDIS_PORT", matches = "\\d+")
class WorkflowExecutionLeaseRedisIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private WorkflowExecutionLeaseService ownerA;
    private WorkflowExecutionLeaseService ownerB;

    @BeforeEach
    void setUp() {
        int port = Integer.parseInt(System.getenv("ROUTER_RECOVERY_REDIS_PORT"));
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        redis.delete(redis.keys("a2a:workflow:{recovery}:lease:*"));
        ownerA = new WorkflowExecutionLeaseService(redis, 3_000L);
        ownerB = new WorkflowExecutionLeaseService(redis, 3_000L);
    }

    @AfterEach
    void tearDown() {
        if (ownerA != null) ownerA.destroy();
        if (ownerB != null) ownerB.destroy();
        if (redis != null) redis.delete(redis.keys("a2a:workflow:{recovery}:lease:*"));
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void staleOwnerCannotRenewOrDeleteReplacementLease() {
        var leaseA = ownerA.acquire("lease-fencing-request");
        assertThat(leaseA).isNotNull();
        assertThat(ownerB.acquire("lease-fencing-request")).isNull();

        redis.delete(redis.keys("a2a:workflow:{recovery}:lease:*"));
        var leaseB = ownerB.acquire("lease-fencing-request");
        assertThat(leaseB).isNotNull();

        assertThatThrownBy(() -> ownerA.assertOwned("lease-fencing-request"))
                .isInstanceOf(WorkflowExecutionLeaseService.WorkflowLeaseLostException.class);
        leaseA.close();
        assertThat(ownerB.isActive("lease-fencing-request")).isTrue();

        leaseB.close();
        assertThat(ownerB.isActive("lease-fencing-request")).isFalse();
    }
}
