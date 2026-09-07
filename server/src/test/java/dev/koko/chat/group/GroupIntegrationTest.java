package dev.koko.chat.group;

import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.*;
import dev.koko.chat.contact.*;
import dev.koko.chat.contact.ContactModels.CreateRequest;
import dev.koko.chat.message.*;
import dev.koko.chat.message.ChatModels.*;
import dev.koko.chat.group.GroupModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 MySQL 验证群权限、成员周期和命令去重；模拟发布之前的事务，不依赖在线设备。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"koko.netty.port=0","koko.messaging.outbox-enabled=false","spring.rabbitmq.listener.simple.auto-startup=false"})
@ActiveProfiles("local") @DirtiesContext
@EnabledIfEnvironmentVariable(named="KOKO_CHAT_AUTH_TEST",matches="true")
class GroupIntegrationTest {
    @Autowired GroupService groups; @Autowired GroupMapper mapper; @Autowired ChatMapper chats; @Autowired ChatService chat;
    @Autowired ContactService contacts; @Autowired ContactMapper relations; @Autowired AuthMapper users; @Autowired AuthService auth;
    @Autowired ObjectMapper json; @Autowired PlatformTransactionManager transactions; @Autowired JdbcTemplate jdbc;
    private final List<Long> people=new ArrayList<>();
    private final Set<Long> owned=new HashSet<>();
    record Person(Identity identity,String account) { String id() { return Long.toString(identity.userId()); } }
    private Person person() {
        String account="it_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        var user=auth.register(new RegisterRequest(account,"Group-test-password!","群聊测试"));people.add(Long.parseLong(user.id()));
        var token=auth.login(new LoginRequest(account,"Group-test-password!","group-test"));
        return new Person(auth.authenticate("Bearer "+token.accessToken()),account);
    }
    private void friends(Person a,Person b) {
        var request=contacts.create(a.identity(),new CreateRequest(b.account(),"测试"));
        contacts.handle(b.identity(),request.id(),true);
    }
    private String command() { return UUID.randomUUID().toString(); }
    private String create(Person owner,List<String> members) {
        var result=groups.create(owner.identity(),new Create(command(),"测试群",members));
        owned.add(Long.parseLong(result.groupId())); return result.groupId();
    }
    private String epoch(Person p,String id) { return groups.detail(p.identity(),id).membershipEpoch(); }
    private MessageView send(Person p,String id,String text) {
        return chat.send(p.identity(),new SendCommand(id,epoch(p,id),command(),text));
    }

    @Test void groupPermissionsJoinVisibilityAndReplayedRemovalRespectNewMembership() {
        var owner=person(); var a=person(); var b=person(); var outsider=person();friends(owner,a);friends(owner,b);
        String id=create(owner,List.of(a.id())), ownerEpoch=epoch(owner,id), oldEpoch=epoch(a,id);
        assertThat(chat.summary(owner.identity(),id).type()).isEqualTo("GROUP");
        assertThatThrownBy(()->groups.detail(outsider.identity(),id)).isInstanceOf(AuthException.class);
        assertThatThrownBy(()->groups.invite(a.identity(),id,new Invite(command(),oldEpoch,List.of(b.id()))))
                .isInstanceOf(AuthException.class).extracting("code").isEqualTo("OWNER_REQUIRED");
        assertThatThrownBy(()->groups.invite(owner.identity(),id,new Invite(command(),ownerEpoch,List.of(outsider.id()))))
                .isInstanceOf(AuthException.class).extracting("code").isEqualTo("FRIEND_REQUIRED");
        send(a,id,"入群前历史");
        groups.invite(owner.identity(),id,new Invite(command(),ownerEpoch,List.of(b.id())));
        assertThat(chat.history(b.identity(),id,"0",null,50).messages()).isEmpty();
        assertThat(chat.history(b.identity(),id,"0",null,50).visibleFromSeq()).isEqualTo("2");
        send(owner,id,"入群后可见");
        assertThat(chat.history(b.identity(),id,"0",null,50).messages()).extracting(MessageView::seq).containsExactly("2");
        assertThat(chat.summary(b.identity(),id).lastReadSeq()).isEqualTo("1");
        assertThat(chat.summary(b.identity(),id).unreadCount()).isEqualTo("1");
        chat.received(a.identity(),new ReceiptCommand(id,oldEpoch,"2"));
        chat.read(a.identity(),new ReadCommand(id,oldEpoch,"2"));
        assertThat(chat.summary(a.identity(),id).peerLastReadSeq()).isNull();
        var removal=new Change(command(),ownerEpoch,oldEpoch);
        groups.remove(owner.identity(),id,a.id(),removal);
        assertThatThrownBy(()->chat.send(a.identity(),new SendCommand(id,oldEpoch,command(),"已被移除"))).isInstanceOf(AuthException.class);
        assertThatThrownBy(()->chat.history(a.identity(),id,"0",null,50)).isInstanceOf(AuthException.class);
        groups.invite(owner.identity(),id,new Invite(command(),ownerEpoch,List.of(a.id())));
        assertThatThrownBy(()->chat.read(a.identity(),new ReadCommand(id,oldEpoch,"2")))
                .isInstanceOf(AuthException.class).extracting("code").isEqualTo("MEMBERSHIP_CHANGED");
        assertThat(chat.summary(a.identity(),id).unreadCount()).isEqualTo("0");
        assertThat(chat.summary(a.identity(),id).lastReadSeq()).isEqualTo("2");
        String newEpoch=epoch(a,id);assertThat(newEpoch).isNotEqualTo(oldEpoch);
        assertThat(chat.history(a.identity(),id,"0",null,50).visibleFromSeq()).isEqualTo("3");
        // 已成功移除的旧命令不能在成员重新入群后再次生效。
        groups.remove(owner.identity(),id,a.id(),removal);
        assertThat(epoch(a,id)).isEqualTo(newEpoch);
        assertThatThrownBy(()->chat.received(a.identity(),new ReceiptCommand(id,oldEpoch,"2"))).isInstanceOf(AuthException.class);
        assertThatThrownBy(()->groups.remove(owner.identity(),id,a.id(),new Change(command(),ownerEpoch,oldEpoch)))
                .isInstanceOf(AuthException.class).extracting("code").isEqualTo("MEMBERSHIP_CHANGED");
        groups.leave(b.identity(),id,new Change(command(),epoch(b,id),null));
        assertThatThrownBy(()->groups.leave(owner.identity(),id,new Change(command(),ownerEpoch,null))).isInstanceOf(AuthException.class);
        var close=new Change(command(),ownerEpoch,null);groups.close(owner.identity(),id,close);groups.close(owner.identity(),id,close);
        assertThat(chats.members(Long.parseLong(id))).isEmpty();
        assertThat(chat.conversations(owner.identity(),"0",100).conversations()).isEmpty();
    }

    @Test void duplicateCreateAndCommandFailureCannotLeavePartialGroups() throws Exception {
        var owner=person();var member=person();friends(owner,member);
        var create=new Create(command(),"幂等群",List.of(member.id()));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->groups.create(owner.identity(),create));var two=pool.submit(()->groups.create(owner.identity(),create));
            var result=one.get(10,TimeUnit.SECONDS);owned.add(Long.parseLong(result.groupId()));
            assertThat(two.get(10,TimeUnit.SECONDS).groupId()).isEqualTo(result.groupId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation WHERE created_by=?",Integer.class,owner.identity().userId())).isEqualTo(1);
            assertThatThrownBy(()->groups.create(owner.identity(),new Create(create.clientCommandId(),"换个群名",List.of(member.id()))))
                    .isInstanceOf(AuthException.class).extracting("code").isEqualTo("COMMAND_CONFLICT");
        }
        var failure=spy(mapper);
        doThrow(new IllegalStateException("injected ledger failure")).when(failure).remember(anyLong(),anyString(),any(byte[].class),anyLong());
        var broken=new GroupService(failure,chats,chat,relations,users,json,transactions);
        assertThatThrownBy(()->broken.create(owner.identity(),new Create(command(),"回滚群",List.of(member.id())))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation WHERE created_by=?",Integer.class,owner.identity().userId())).isEqualTo(1);
    }

    @Test void simultaneousInvitesCannotExceedTwoHundredMembers() throws Exception {
        var owner=person();var a=person();var b=person();friends(owner,a);friends(owner,b);
        String id=create(owner,List.of());long group=Long.parseLong(id);
        // 只构造边界前置数据，这些无登录凭证的账号不会参与业务请求。
        var random=new SecureRandom();
        for(int i=0;i<198;i++) {
            long user=random.nextLong(1,Long.MAX_VALUE);people.add(user);
            jdbc.update("INSERT INTO app_user(id,account,password_hash,nickname) VALUES(?,?,?,?)",user,"it_"+UUID.randomUUID().toString().replace("-","").substring(0,20),"fixture-no-login","人数边界成员");
            mapper.join(group,user,"MEMBER",command(),1,0);
        }
        String epoch=epoch(owner,id);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CyclicBarrier(2);
            Callable<String> left=()->{gate.await();try { groups.invite(owner.identity(),id,new Invite(command(),epoch,List.of(a.id())));return "OK"; }catch(AuthException e){return e.code();}};
            Callable<String> right=()->{gate.await();try { groups.invite(owner.identity(),id,new Invite(command(),epoch,List.of(b.id())));return "OK"; }catch(AuthException e){return e.code();}};
            var one=pool.submit(left);var two=pool.submit(right);
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","GROUP_FULL");
            assertThat(groups.detail(owner.identity(),id).members()).hasSize(200);
        }
    }
    @AfterEach void cleanup() {
        for(long id:owned) {
            jdbc.update("DELETE FROM group_command WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM device_cursor WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM message_outbox WHERE message_id IN (SELECT id FROM message WHERE conversation_id=?)",id);
            jdbc.update("DELETE FROM message WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM conversation_member WHERE conversation_id=?",id);
            jdbc.update("DELETE FROM conversation WHERE id=?",id);
        }
        for(long id:people) { jdbc.update("DELETE FROM friendship WHERE user_id=? OR friend_id=?",id,id);jdbc.update("DELETE FROM friend_request WHERE sender_id=? OR receiver_id=?",id,id); }
        for(long id:people) { jdbc.update("DELETE FROM auth_session WHERE user_id=?",id);jdbc.update("DELETE FROM app_user WHERE id=?",id); }
    }
}
