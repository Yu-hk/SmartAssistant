package com.example.smartassistant.consumer.service.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessageBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Dedicated broker queues plus guarded disposable PG. Never probes production defaults. */
@Tag("integration")
@EnabledIfSystemProperty(named="profile.pg.integration",matches="true")
@EnabledIfEnvironmentVariable(named="CHAT_DISPATCH_TEST_RABBIT_PORT",matches="\\d+")
class ProfileCommitBrokerIntegrationTest {
    final ProfileCommitCandidateIntegrationTest fixture=new ProfileCommitCandidateIntegrationTest();
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    @BeforeAll static void database() { ProfileCommitCandidateIntegrationTest.database(); }
    @BeforeEach void setup() { fixture.setup(); }
    @AfterEach void cleanup() { fixture.cleanup(); }
    @Test void confirmedReferenceCommitsAndDuplicateDeliveryCannotRecreatePayload() throws Exception { roundTrip(false); }
    @Test void deadLetterContainsOnlyReferenceNotProfileOrUserText() throws Exception { roundTrip(true); }

    void roundTrip(boolean deadLetter) throws Exception {
        var factory=new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(System.getenv().getOrDefault("CHAT_DISPATCH_TEST_RABBIT_HOST","127.0.0.1"));
        factory.setPort(Integer.parseInt(System.getenv("CHAT_DISPATCH_TEST_RABBIT_PORT")));
        factory.setUsername(java.util.Objects.requireNonNull(System.getenv("CHAT_DISPATCH_TEST_RABBIT_USERNAME")));
        factory.setPassword(java.util.Objects.requireNonNull(System.getenv("CHAT_DISPATCH_TEST_RABBIT_PASSWORD")));
        String queue="test.profile-reference."+UUID.randomUUID(), dlq=queue+".dlq";
        var spring=new CachingConnectionFactory(factory);
        spring.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); spring.setPublisherReturns(true);
        try (var connection=factory.newConnection(); var channel=connection.createChannel()) {
            channel.queueDeclare(dlq,true,false,false,Map.of("x-expires",600000));
            channel.queueDeclare(queue,true,false,false,Map.of("x-expires",600000,"x-dead-letter-exchange","","x-dead-letter-routing-key",dlq));
            try {
                var template=new RabbitTemplate(spring); template.setMandatory(true);
                new UserProfileCommitPublisher(template,json,fixture.store,"",queue,5000).publish(fixture.candidate());
                var delivery=get(channel,queue); assertEquals(2,delivery.getProps().getDeliveryMode());
                if (deadLetter) {
                    channel.basicReject(delivery.getEnvelope().getDeliveryTag(),false); delivery=get(channel,dlq);
                }
                String wire=new String(delivery.getBody(),StandardCharsets.UTF_8);
                assertFalse(wire.contains("fixture private text")); assertFalse(wire.contains("commerceAssessment"));
                assertFalse(wire.contains("latestUserMessage")); assertFalse(wire.contains("\"candidate\""));
                var event=json.readValue(wire,UserProfileCommitRequestedEvent.class); assertEquals("2",event.version());
                assertTrue(fixture.store.resolve(event).isPresent());
                if (!deadLetter) {
                    var service=new UserProfileService(null,fixture.fixture.store,null);
                    var listener=new UserProfileCommitListener(json,service,fixture.store);
                    var message=MessageBuilder.withBody(delivery.getBody()).build();
                    listener.receive(message); listener.receive(message);
                    assertEquals(1,fixture.fixture.store.load(fixture.fixture.user).orElseThrow().profileVersion());
                    assertTrue(fixture.store.resolve(event).isEmpty());
                }
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
            } finally { channel.queueDelete(queue); channel.queueDelete(dlq); }
        } finally { spring.destroy(); }
    }
    static com.rabbitmq.client.GetResponse get(com.rabbitmq.client.Channel channel,String queue) throws Exception {
        for (int i=0;i<50;i++) { var response=channel.basicGet(queue,false); if(response!=null)return response; Thread.sleep(100); }
        throw new AssertionError("Dedicated queue delivery timed out");
    }
}
