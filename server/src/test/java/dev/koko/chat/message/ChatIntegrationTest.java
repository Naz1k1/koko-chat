package dev.koko.chat.message;

import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.*;
import dev.koko.chat.message.ChatModels.*;
import dev.koko.chat.message.mq.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL/Redis/RabbitMQ 验证事务、幂等、权限、租约与 mandatory return；只清理本测试资源。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"koko.netty.port=0","koko.messaging.outbox-enabled=false"})
@ActiveProfiles("local") @DirtiesContext
@EnabledIfEnvironmentVariable(named="KOKO_CHAT_AUTH_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatIntegrationTest {
    static final String PREFIX="it-chat-"+UUID.randomUUID().toString().substring(0,8);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("koko.messaging.prefix",()->PREFIX); }
    @Autowired AuthService auth;@Autowired ChatService chat;@Autowired ChatMapper mapper;@Autowired JdbcTemplate jdbc;
    @Autowired OutboxMapper outbox;@Autowired OutboxPublisher publisher;@Autowired ConfirmedPublisher confirmed;
    @Autowired MessagingTopology topology;@Autowired PlatformTransactionManager transactions;
    @Autowired RabbitAdmin admin;@Autowired RabbitListenerEndpointRegistry listeners;
    private final List<Long> users=new ArrayList<>();private final Set<Long> conversations=new HashSet<>();
    record Person(Identity identity,String account) {}
    private Person person() {
        String account="it_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        var user=auth.register(new RegisterRequest(account,"Integration-only-password!","测试用户"));users.add(Long.parseLong(user.id()));
        var tokens=auth.login(new LoginRequest(account,"Integration-only-password!","test-device"));
        return new Person(auth.authenticate("Bearer "+tokens.accessToken()),account);
    }
    private ConversationView conversation(Person a,Person b) {
        var result=chat.createDirect(a.identity(),b.account());conversations.add(Long.parseLong(result.id()));return result;
    }
    @Test void concurrentCreationAndDuplicateSendKeepOneConversationAndOneMessage() throws Exception {
        var a=person();var b=person();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CyclicBarrier(2);
            var left=pool.submit(()->{gate.await();return chat.createDirect(a.identity(),b.account());});
            var right=pool.submit(()->{gate.await();return chat.createDirect(b.identity(),a.account());});
            var view=left.get(10,TimeUnit.SECONDS);conversations.add(Long.parseLong(view.id()));
            assertThat(right.get(10,TimeUnit.SECONDS).id()).isEqualTo(view.id());
            var command=new SendCommand(view.id(),view.membershipEpoch(),UUID.randomUUID().toString(),"你好，单聊");
            var first=pool.submit(()->chat.send(a.identity(),command));var second=pool.submit(()->chat.send(a.identity(),command));
            var sent=first.get(10,TimeUnit.SECONDS);assertThat(second.get(10,TimeUnit.SECONDS).id()).isEqualTo(sent.id());
            assertThat(sent.seq()).isEqualTo("1");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE message_id=?",Integer.class,sent.id())).isEqualTo(1);
            assertThatThrownBy(()->chat.send(a.identity(),new SendCommand(view.id(),view.membershipEpoch(),command.clientMsgId(),"不同内容")))
                    .isInstanceOf(AuthException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        }
    }
    @Test void historyIsBoundedReceiptsAreMonotonicAndOtherUsersCannotRead() {
        var a=person();var b=person();var outsider=person();var view=conversation(a,b);
        for(int i=0;i<3;i++) chat.send(a.identity(),new SendCommand(view.id(),view.membershipEpoch(),UUID.randomUUID().toString(),"消息"+i));
        var bob=chat.createDirect(b.identity(),a.account());
        var page=chat.history(b.identity(),view.id(),"0","2",1);
        assertThat(page.messages()).hasSize(1);assertThat(page.hasMore()).isTrue();
        assertThat(chat.history(b.identity(),view.id(),page.nextCursor(),page.toSeq(),10).messages()).extracting(MessageView::seq).containsExactly("2");
        chat.received(b.identity(),new ReceiptCommand(view.id(),bob.membershipEpoch(),"2"));
        chat.received(b.identity(),new ReceiptCommand(view.id(),bob.membershipEpoch(),"1"));
        assertThat(jdbc.queryForObject("SELECT received_seq FROM device_cursor WHERE user_id=? AND conversation_id=?",Long.class,b.identity().userId(),view.id())).isEqualTo(2);
        assertThatThrownBy(()->chat.received(b.identity(),new ReceiptCommand(view.id(),bob.membershipEpoch(),"4"))).isInstanceOf(AuthException.class);
        assertThatThrownBy(()->chat.history(outsider.identity(),view.id(),"0",null,50)).isInstanceOf(AuthException.class);
        assertThatThrownBy(()->chat.send(a.identity(),new SendCommand(view.id(),UUID.randomUUID().toString(),"stale","旧成员周期"))).isInstanceOf(AuthException.class);
    }
    @Test void outboxFailureRollsBackMessageAndSequenceAndExpiredLeaseCanRecover() throws Exception {
        var a=person();var b=person();var view=conversation(a,b);
        ChatMapper failure=spy(mapper);
        doThrow(new IllegalStateException("injected outbox failure")).when(failure).insertOutbox(anyString(),anyLong(),anyString());
        var broken=new ChatService(failure,org.mockito.Mockito.mock(AuthMapper.class),auth,new com.fasterxml.jackson.databind.ObjectMapper(),transactions);
        var command=new SendCommand(view.id(),view.membershipEpoch(),UUID.randomUUID().toString(),"必须原子提交");
        assertThatThrownBy(()->broken.send(a.identity(),command)).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.conversation(Long.parseLong(view.id())).latestSeq()).isZero();
        assertThat(mapper.byClient(a.identity().userId(),command.clientMsgId())).isNull();
        var sent=chat.send(a.identity(),command);
        String event=jdbc.queryForObject("SELECT event_id FROM message_outbox WHERE message_id=?",String.class,sent.id());
        jdbc.update("UPDATE message_outbox SET status='PUBLISHING',lease_token=?,lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE event_id=?",UUID.randomUUID().toString(),event);
        for(int i=0;i<10;i++) publisher.publishBatch();
        assertThat(jdbc.queryForObject("SELECT status FROM message_outbox WHERE event_id=?",String.class,event)).isEqualTo("PUBLISHED");
        assertThat(outbox.published(event,UUID.randomUUID().toString())).isZero();
        assertThatThrownBy(()->confirmed.publish(topology.name("message.x"),"unbound.test",new byte[]{123,125},UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
    }
    @AfterAll void cleanup() {
        listeners.stop();
        for(long id:conversations) {
            jdbc.update("DELETE FROM device_cursor WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM message_outbox WHERE message_id IN (SELECT id FROM message WHERE conversation_id=?)",id);
            jdbc.update("DELETE FROM message WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM conversation_member WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM conversation WHERE id=?",id);
        }
        for(long id:users) { jdbc.update("DELETE FROM auth_session WHERE user_id=?",id);jdbc.update("DELETE FROM app_user WHERE id=?",id); }
        for(String suffix:List.of("dispatch.q","dispatch.dlq","dispatch.retry.5s.q","dispatch.retry.30s.q","dispatch.retry.120s.q")) admin.deleteQueue(topology.name(suffix));
        admin.deleteQueue(topology.gatewayQueueName());
        for(String suffix:List.of("message.x","gateway.x","retry.x","dead.x")) admin.deleteExchange(topology.name(suffix));
    }
}
