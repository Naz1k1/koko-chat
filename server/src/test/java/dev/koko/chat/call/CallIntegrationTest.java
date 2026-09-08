package dev.koko.chat.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.*;
import dev.koko.chat.message.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 验证跨设备抢接、忙线、信令访问、幂等与超时；数据只清理本测试拥有的会话。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"koko.netty.port=0","koko.messaging.outbox-enabled=false","spring.rabbitmq.listener.simple.auto-startup=false"})
@ActiveProfiles("local") @DirtiesContext @EnabledIfEnvironmentVariable(named="KOKO_CHAT_AUTH_TEST",matches="true")
class CallIntegrationTest {
    @Autowired CallService calls;@Autowired CallMapper mapper;@Autowired ChatService chat;@Autowired AuthService auth;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    private final List<Long> people=new ArrayList<>();private final List<String> conversations=new ArrayList<>();
    record Person(Identity identity,String account) {}
    private Person person() { String account="it_"+UUID.randomUUID().toString().replace("-","").substring(0,20);var user=auth.register(new RegisterRequest(account,"Call-test-password!","语音测试"));people.add(Long.parseLong(user.id()));return login(account); }
    private Person login(String account) { var tokens=auth.login(new LoginRequest(account,"Call-test-password!",UUID.randomUUID().toString()));return new Person(auth.authenticate("Bearer "+tokens.accessToken()),account); }
    private ObjectNode command(String action,String id) {return json.createObjectNode().put("action",action).put("callId",id);}
    @Test void concurrentAcceptBindsOneDeviceAndSignalsStayPrivate() throws Exception {
        var a=person();var b=person();var other=login(b.account());var stranger=person();var conversation=chat.createDirect(a.identity(),b.account());conversations.add(conversation.id());
        String id=UUID.randomUUID().toString();var create=command("CREATE",id).put("conversationId",conversation.id()).put("membershipEpoch",conversation.membershipEpoch());
        assertThat(calls.command(a.identity(),create).call().state()).isEqualTo("RINGING");
        assertThat(calls.command(a.identity(),create).call().id()).isEqualTo(id);
        assertThatThrownBy(()->calls.command(a.identity(),create.deepCopy().put("callId",UUID.randomUUID().toString()))).isInstanceOf(AuthException.class).extracting("code").isEqualTo("CALL_BUSY");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);List<Future<Boolean>> results=new ArrayList<>();
            for(var p:List.of(b,other)) results.add(pool.submit(()->{barrier.await();try {calls.command(p.identity(),command("ACCEPT",id));return true;}catch(AuthException expected){return false;}}));
            assertThat(results.get(0).get(5,TimeUnit.SECONDS) ^ results.get(1).get(5,TimeUnit.SECONDS)).isTrue();
        }
        var winner=mapper.find(id).calleeSession().equals(b.identity().sessionId())?b:other;var loser=winner==b?other:b;
        assertThat(calls.command(loser.identity(),command("SYNC",id)).call()).isNull();
        assertThatThrownBy(()->calls.command(loser.identity(),command("END",id))).isInstanceOf(AuthException.class);
        assertThatThrownBy(()->calls.command(stranger.identity(),command("SYNC",id))).isInstanceOf(AuthException.class);
        var offer=command("SIGNAL",id).put("signalId",UUID.randomUUID().toString()).put("kind","OFFER").put("payload","v=0\r\n");
        calls.command(a.identity(),offer);calls.command(a.identity(),offer);
        assertThat(mapper.signalCount(id)).isEqualTo(1);
        assertThat(calls.command(winner.identity(),command("SYNC",id)).signals()).hasSize(1);
        assertThat(calls.command(a.identity(),command("SYNC",id)).signals()).isEmpty();
        calls.command(a.identity(),command("CONNECTED",id));calls.command(winner.identity(),command("CONNECTED",id));
        assertThat(mapper.find(id).state()).isEqualTo("ACTIVE");
        calls.command(winner.identity(),command("END",id));assertThat(mapper.find(id).state()).isEqualTo("ENDED");assertThat(mapper.signalCount(id)).isZero();
        assertThat(calls.command(a.identity(),offer).signals()).isEmpty();
        String next=UUID.randomUUID().toString();calls.command(a.identity(),create.deepCopy().put("callId",next));
        jdbc.update("UPDATE call_session SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",next);calls.expireCalls();
        assertThat(mapper.find(next).reason()).isEqualTo("TIMEOUT");assertThat(calls.command(b.identity(),command("ACCEPT",next)).call().state()).isEqualTo("ENDED");
    }
    @Test void videoTypeAndCameraStateAreBoundToCallAndDevice() {
        var a=person();var b=person();var other=login(b.account());
        var conversation=chat.createDirect(a.identity(),b.account());conversations.add(conversation.id());
        String id=UUID.randomUUID().toString();
        var create=command("CREATE",id).put("conversationId",conversation.id()).put("membershipEpoch",conversation.membershipEpoch());
        assertThatThrownBy(()->calls.command(a.identity(),create.deepCopy().put("mediaType","SCREEN"))).isInstanceOf(AuthException.class);
        create.put("mediaType","VIDEO");
        assertThat(calls.command(a.identity(),create).call().mediaType()).isEqualTo("VIDEO");
        assertThatThrownBy(()->calls.command(a.identity(),create.deepCopy().put("mediaType","AUDIO"))).isInstanceOf(AuthException.class).extracting("code").isEqualTo("CALL_CONFLICT");
        var camera=command("MEDIA",id).put("cameraEnabled",true);
        assertThatThrownBy(()->calls.command(a.identity(),camera)).isInstanceOf(AuthException.class);
        calls.command(b.identity(),command("ACCEPT",id));
        assertThatThrownBy(()->calls.command(other.identity(),camera)).isInstanceOf(AuthException.class);
        assertThatThrownBy(()->calls.command(a.identity(),camera.deepCopy().put("cameraEnabled","true"))).isInstanceOf(AuthException.class);
        calls.command(a.identity(),camera);calls.command(a.identity(),camera);
        var view=calls.command(b.identity(),command("SYNC",id)).call();
        assertThat(view.callerCamera()).isTrue();assertThat(view.calleeCamera()).isFalse();
        calls.command(a.identity(),camera.deepCopy().put("cameraEnabled",false));
        assertThat(mapper.find(id).callerCamera()).isFalse();
        // 音视频 SDP 扩容，但快照按字节分页，超过限制的信令仍被拒绝。
        for(String kind:List.of("OFFER","ICE")) calls.command(a.identity(),command("SIGNAL",id).put("signalId",UUID.randomUUID().toString()).put("kind",kind).put("payload","x".repeat(7000)));
        var first=calls.command(b.identity(),command("SYNC",id)).signals();assertThat(first).hasSize(1);
        assertThat(calls.command(b.identity(),command("SYNC",id).put("after",first.getFirst().id())).signals()).hasSize(1);
        assertThatThrownBy(()->calls.command(a.identity(),command("SIGNAL",id).put("signalId",UUID.randomUUID().toString()).put("kind","ICE").put("payload","x".repeat(12289)))).isInstanceOf(AuthException.class);
        calls.command(b.identity(),camera);calls.command(a.identity(),command("END",id));
        assertThat(mapper.find(id).calleeCamera()).isFalse();assertThat(mapper.signalCount(id)).isZero();
        create.remove("mediaType");create.put("callId",UUID.randomUUID().toString());
        assertThat(calls.command(a.identity(),create).call().mediaType()).isEqualTo("AUDIO");
    }
    @AfterEach void cleanup() {
        for(String conversation:conversations) {
            jdbc.update("DELETE FROM call_signal WHERE call_id IN (SELECT id FROM call_session WHERE conversation_id=?)",conversation);
            jdbc.update("DELETE FROM call_session WHERE conversation_id=?",conversation);
            jdbc.update("DELETE FROM conversation_member WHERE conversation_id=?",conversation);jdbc.update("DELETE FROM conversation WHERE id=?",conversation);
        }
        for(long id:people) {jdbc.update("DELETE FROM auth_session WHERE user_id=?",id);jdbc.update("DELETE FROM app_user WHERE id=?",id);}
    }
}
