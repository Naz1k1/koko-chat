package dev.koko.chat.contact;

import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.*;
import dev.koko.chat.contact.ContactModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 MySQL 校验好友状态机、用户对锁与双向关系原子性；只清理本测试账号。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"koko.netty.port=0", "koko.messaging.outbox-enabled=false", "spring.rabbitmq.listener.simple.auto-startup=false"})
@ActiveProfiles("local") @DirtiesContext
@EnabledIfEnvironmentVariable(named="KOKO_CHAT_AUTH_TEST", matches="true")
class ContactIntegrationTest {
    @Autowired ContactService contacts;
    @Autowired ContactMapper mapper;
    @Autowired AuthService auth;
    @Autowired AuthMapper users;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    private final List<Long> ids = new ArrayList<>();
    record Person(String account, String access, Identity identity) {}
    private Person person() {
        String account = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var user = auth.register(new RegisterRequest(account, "Contact-test-password!", "联系人测试"));
        ids.add(Long.parseLong(user.id()));
        var tokens = auth.login(new LoginRequest(account, "Contact-test-password!", "contact-test"));
        return new Person(account, tokens.accessToken(), auth.authenticate("Bearer " + tokens.accessToken()));
    }
    private RequestView apply(Person a, Person b) {
        return contacts.create(a.identity(), new CreateRequest(b.account(), "你好，一起交流"));
    }
    @AfterEach void cleanup() {
        for (long id : ids) {
            jdbc.update("DELETE FROM friendship WHERE user_id=? OR friend_id=?", id, id);
            jdbc.update("DELETE FROM friend_request WHERE sender_id=? OR receiver_id=?", id, id);
        }
        for (long id : ids) {
            jdbc.update("DELETE FROM auth_session WHERE user_id=?", id);
            jdbc.update("DELETE FROM app_user WHERE id=?", id);
        }
    }

    @Test void simultaneousOppositeRequestsAndRepeatedAcceptCreateExactlyTwoRelations() throws Exception {
        var a = person(); var b = person();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<Object> left = () -> { barrier.await(); try { return apply(a, b); } catch (AuthException e) { return e.code(); } };
            Callable<Object> right = () -> { barrier.await(); try { return apply(b, a); } catch (AuthException e) { return e.code(); } };
            var first = pool.submit(left); var second = pool.submit(right);
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(RequestView.class::isInstance)).hasSize(1);
            assertThat(results).contains("INCOMING_REQUEST_PENDING");
            var request = results.stream().filter(RequestView.class::isInstance).map(RequestView.class::cast).findFirst().orElseThrow();
            Person sender = request.senderId().equals(Long.toString(a.identity().userId())) ? a : b;
            Person receiver = sender == a ? b : a;
            assertThat(apply(sender, receiver).id()).isEqualTo(request.id());
            var x = pool.submit(() -> contacts.handle(receiver.identity(), request.id(), true));
            var y = pool.submit(() -> contacts.handle(receiver.identity(), request.id(), true));
            assertThat(x.get(10, TimeUnit.SECONDS).status()).isEqualTo("ACCEPTED");
            assertThat(y.get(10, TimeUnit.SECONDS).status()).isEqualTo("ACCEPTED");
            assertThat(contacts.friends(a.identity(), "0", 50).friends()).extracting(FriendView::id).containsExactly(Long.toString(b.identity().userId()));
            assertThat(contacts.friends(b.identity(), "0", 50).friends()).extracting(FriendView::id).containsExactly(Long.toString(a.identity().userId()));
            assertThatThrownBy(() -> apply(a, b)).isInstanceOf(AuthException.class).extracting("code").isEqualTo("ALREADY_FRIENDS");
        }
    }

    @Test void secondRelationFailureRollsBackAndRejectAllowsAnotherRequest() {
        var a = person(); var b = person(); var request = apply(a, b);
        var failing = spy(mapper);
        doThrow(new IllegalStateException("injected second insert failure")).when(failing).insertFriend(b.identity().userId(), a.identity().userId());
        var broken = new ContactService(failing, users, auth, transactions);
        assertThatThrownBy(() -> broken.handle(b.identity(), request.id(), true)).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.friends(a.identity().userId(), b.identity().userId())).isZero();
        assertThat(mapper.friends(b.identity().userId(), a.identity().userId())).isZero();
        assertThat(mapper.request(Long.parseLong(request.id())).status()).isEqualTo("PENDING");
        assertThat(contacts.handle(b.identity(), request.id(), false).status()).isEqualTo("REJECTED");
        assertThat(contacts.handle(b.identity(), request.id(), false).handledAt()).isNotNull();
        assertThatThrownBy(() -> contacts.handle(b.identity(), request.id(), true)).isInstanceOf(AuthException.class).extracting("code").isEqualTo("REQUEST_HANDLED");
        assertThat(apply(a, b).id()).isNotEqualTo(request.id());
        var page = contacts.requests(a.identity(), "0", 1, "outgoing", "ALL");
        assertThat(page.hasMore()).isTrue();
        assertThat(contacts.requests(a.identity(), page.nextCursor(), 1, "outgoing", "ALL").requests()).hasSize(1);
        assertThat(contacts.requests(b.identity(), "0", 50, "incoming", "PENDING").requests()).hasSize(1);
    }

    @Test void httpRequiresIdentityAndOnlyReceiverCanDecide() {
        var a = person(); var b = person(); var outsider = person();
        var headers = new HttpHeaders(); headers.setBearerAuth(a.access());
        assertThat(http.getForEntity("/api/friends", String.class).getStatusCode().value()).isEqualTo(401);
        var response = http.exchange("/api/friend-requests", HttpMethod.POST,
                new HttpEntity<>(new CreateRequest(b.account().toUpperCase(Locale.ROOT), "你好"), headers), RequestView.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var request = response.getBody(); assertThat(request).isNotNull();
        for (Person actor : List.of(a, outsider)) {
            var token = new HttpHeaders(); token.setBearerAuth(actor.access());
            assertThat(http.exchange("/api/friend-requests/" + request.id() + "/accept", HttpMethod.POST,
                    new HttpEntity<>(token), String.class).getStatusCode().value()).isEqualTo(404);
        }
        assertThat(contacts.requests(outsider.identity(), "0", 100, "all", "ALL").requests()).isEmpty();
        assertThatThrownBy(() -> apply(a, a)).isInstanceOf(AuthException.class).extracting("code").isEqualTo("SELF_REQUEST");
        assertThat(http.exchange("/api/friend-requests", HttpMethod.POST,
                new HttpEntity<>(new CreateRequest(b.account(), "x".repeat(256)), headers), String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.exchange("/api/friends?afterId=-1", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.exchange("/api/friend-requests?direction=invalid", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(400);
    }
}
