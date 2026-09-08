package dev.koko.chat.ops;

import com.fasterxml.jackson.databind.*;
import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.*;
import dev.koko.chat.message.*;
import dev.koko.chat.message.ChatModels.*;
import dev.koko.chat.message.mq.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实队列归档、持久审计、重放租约、原编号重试与运维认证。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"koko.netty.port=0","koko.messaging.outbox-enabled=false","spring.rabbitmq.listener.simple.auto-startup=false","koko.ops.jobs-enabled=false","KOKO_OPS_TOKEN=operations-test-credential-only-32-chars"})
@ActiveProfiles("local") @DirtiesContext @EnabledIfEnvironmentVariable(named="KOKO_CHAT_AUTH_TEST",matches="true")
class OperationsIntegrationTest {
    static final String PREFIX="it-ops-"+UUID.randomUUID().toString().substring(0,8);
    static final String TOKEN="operations-test-credential-only-32-chars";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("koko.messaging.prefix",()->PREFIX); }
    @Autowired OperationsService ops;@Autowired OperationsMonitor monitor;@Autowired JdbcTemplate jdbc;@Autowired ChatMapper mapper;
    @Autowired AuthService auth;@Autowired ChatService chat;@Autowired ObjectMapper json;@Autowired RabbitTemplate rabbit;@Autowired RabbitAdmin admin;
    @Autowired ConfirmedPublisher publisher;@Autowired MessagingTopology topology;@Autowired OpsAccess access;@Autowired PlatformTransactionManager tx;@Autowired TestRestTemplate http;
    private final List<Long> users=new ArrayList<>();private String conversation;
    private Identity user() {String account="it_"+UUID.randomUUID().toString().replace("-","").substring(0,20);var user=auth.register(new RegisterRequest(account,"Ops-test-password!","运维测试"));users.add(Long.parseLong(user.id()));var token=auth.login(new LoginRequest(account,"Ops-test-password!",UUID.randomUUID().toString()));return auth.authenticate("Bearer "+token.accessToken());}
    private ResponseEntity<String> get(String path,String token) {var headers=new HttpHeaders();if(token!=null) headers.setBearerAuth(token);return http.exchange("/internal/ops/"+path,HttpMethod.GET,new HttpEntity<>(headers),String.class);}
    @Test void archiveRetryReplayAuditAndMetricsAreProtected() throws Exception {
        admin.initialize();var a=user();var b=user();String peer=jdbc.queryForObject("SELECT account FROM app_user WHERE id=?",String.class,b.userId());
        var view=chat.createDirect(a,peer);conversation=view.id();var sent=chat.send(a,new SendCommand(view.id(),view.membershipEpoch(),UUID.randomUUID().toString(),"运维接口不得展示此正文"));
        byte[] original=jdbc.queryForObject("SELECT payload FROM message_outbox WHERE message_id=?",String.class,sent.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var event=json.readValue(original,MessageEvent.class);
        var targeted=new MessageEvent(1,"message.created",event.eventId(),event.messageId(),event.conversationId(),event.seq(),4,Long.toString(b.userId()),b.deviceId());
        byte[] body=json.writeValueAsBytes(targeted);
        publisher.publish(topology.name("dead.x"),"dispatch.dead",body,event.eventId());
        var broken=spy(ops);doThrow(new IllegalStateException("injected archive failure")).when(broken).archive(any(byte[].class));
        assertThatThrownBy(broken::importBatch).isInstanceOf(Exception.class);
        assertThat(ops.importBatch()).isEqualTo(1); // 归档失败不能提前 ACK 原件。
        publisher.publish(topology.name("dead.x"),"dispatch.dead",body,event.eventId());assertThat(ops.importBatch()).isEqualTo(1);
        var dead=ops.list(null,20).items().getFirst();assertThat(dead.occurrences()).isEqualTo(2);assertThat(dead.replayable()).isTrue();
        assertThat(get("dead-letters",null).getStatusCode().value()).isEqualTo(401);
        assertThat(get("dead-letters","ordinary-chat-token").getStatusCode().value()).isEqualTo(401);
        var listed=get("dead-letters",TOKEN);assertThat(listed.getStatusCode().value()).isEqualTo(200);assertThat(listed.getBody()).doesNotContain("运维接口不得展示此正文","payload","body");
        assertThat(get("dead-letters?limit=500",TOKEN).getStatusCode().value()).isEqualTo(400);
        String request=UUID.randomUUID().toString();var command=new OperationsService.Command(request,"修复路由后重新投递");
        var action=ops.submit(dead.id(),"REPLAY",command);assertThat(ops.submit(dead.id(),"REPLAY",command)).isEqualTo(action);
        assertThatThrownBy(()->ops.submit(dead.id(),"REPLAY",new OperationsService.Command(request,"不同原因"))).isInstanceOf(AuthException.class);
        assertThatThrownBy(()->ops.submit(dead.id(),"REPLAY",new OperationsService.Command(UUID.randomUUID().toString(),"重复重放"))).isInstanceOf(AuthException.class);
        var unavailable=mock(ConfirmedPublisher.class);doThrow(new IllegalStateException("injected MQ failure")).when(unavailable).publish(anyString(),anyString(),any(byte[].class),anyString());
        new OperationsService(jdbc,mapper,json,rabbit,unavailable,topology,access,tx,false,"local-operator").publishBatch();
        assertThat(ops.action(request).status()).isEqualTo("PENDING");assertThat(ops.action(request).attempts()).isEqualTo(1);
        // 模拟发布者持有租约后退出，后续实例重新认领；过期工作者的令牌失效。
        jdbc.update("UPDATE ops_action SET status='PUBLISHING',lease_token=?,lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",UUID.randomUUID().toString(),request);
        ops.publishBatch();assertThat(ops.action(request).status()).isEqualTo("PUBLISHED");
        var delivered=rabbit.receive(topology.name("dispatch.q"),3000);assertThat(delivered).isNotNull();var replay=json.readValue(delivered.getBody(),MessageEvent.class);
        assertThat(replay.eventId()).isEqualTo(event.eventId());assertThat(replay.attempt()).isZero();assertThat(replay.recipientUserId()).isEqualTo(Long.toString(b.userId()));assertThat(replay.recipientDeviceId()).isEqualTo(b.deviceId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message WHERE id=?",Integer.class,sent.id())).isEqualTo(1);
        String poison=ops.archive(new byte[]{123});assertThatThrownBy(()->ops.submit(poison,"REPLAY",new OperationsService.Command(UUID.randomUUID().toString(),"无效事件"))).isInstanceOf(AuthException.class);
        assertThat(monitor.refresh().alerts()).contains("DEAD_LETTERS_PENDING");
        ops.submit(poison,"ACK",new OperationsService.Command(UUID.randomUUID().toString(),"确认畸形事件，保留归档不重放"));
        assertThat(monitor.refresh().alerts()).doesNotContain("DEAD_LETTERS_PENDING");
        var metrics=get("metrics",TOKEN).getBody();assertThat(metrics).contains("koko_mysql_up 1.0","koko_rustfs_up 1.0","koko_snapshot_age_seconds");
        if(System.getenv("KOKO_CHAT_OPS_METRICS_FILE")!=null) java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("KOKO_CHAT_OPS_METRICS_FILE")),metrics);
        jdbc.update("UPDATE message_outbox SET created_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE) WHERE message_id=?",sent.id());
        assertThat(monitor.refresh().alerts()).contains("OUTBOX_BACKLOG");
        jdbc.update("UPDATE message_outbox SET status='PUBLISHED',published_at=UTC_TIMESTAMP(3) WHERE message_id=?",sent.id());
        assertThat(monitor.refresh().alerts()).doesNotContain("OUTBOX_BACKLOG");
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE a FROM ops_action a JOIN ops_dead_letter d ON a.dead_id=d.id WHERE d.scope=?",PREFIX);jdbc.update("DELETE FROM ops_dead_letter WHERE scope=?",PREFIX);
        if(conversation!=null) {jdbc.update("DELETE FROM message_outbox WHERE message_id IN(SELECT id FROM message WHERE conversation_id=?)",conversation);jdbc.update("DELETE FROM message WHERE conversation_id=?",conversation);jdbc.update("DELETE FROM conversation_member WHERE conversation_id=?",conversation);jdbc.update("DELETE FROM conversation WHERE id=?",conversation);}
        for(long id:users) {jdbc.update("DELETE FROM auth_session WHERE user_id=?",id);jdbc.update("DELETE FROM app_user WHERE id=?",id);}
        for(String suffix:List.of("dispatch.q","dispatch.dlq","dispatch.retry.5s.q","dispatch.retry.30s.q","dispatch.retry.120s.q")) admin.deleteQueue(topology.name(suffix));admin.deleteQueue(topology.gatewayQueueName());
        for(String suffix:List.of("message.x","gateway.x","retry.x","dead.x")) admin.deleteExchange(topology.name(suffix));
    }
}
