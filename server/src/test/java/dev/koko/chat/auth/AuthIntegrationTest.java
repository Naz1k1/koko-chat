package dev.koko.chat.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.koko.chat.transport.netty.NettyWebSocketServer;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 仅显式启用时连接本机开发中间件；只清理本测试创建的随机账号，不清空业务表。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "koko.netty.port=0")
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named="KOKO_CHAT_AUTH_TEST", matches="true")
class AuthIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired NettyWebSocketServer netty;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    private final List<String> accounts = new ArrayList<>();
    private static final String PASSWORD = "Test-only-password-817!";

    @AfterEach void cleanup() {
        for (String account : accounts) {
            jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM app_user WHERE account=?)", account);
            jdbc.update("DELETE FROM app_user WHERE account=?", account);
        }
    }

    @Test void registrationLoginRotationReplacementAndLogout() throws Exception {
        String account = register();
        assertThat(post("/register", Map.of("account", account.toUpperCase(Locale.ROOT), "password", PASSWORD, "nickname", "重复")).getStatusCode().value()).isEqualTo(409);
        assertThat(post("/login", Map.of("account", account, "password", "wrong-password", "deviceId", "desktop-a")).getStatusCode().value()).isEqualTo(401);
        var first = login(account, "desktop-a");
        assertThat(me(first).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE account=?", String.class, account)).startsWith("{pbkdf2-v1}").doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject("SELECT LENGTH(access_token_hash) FROM auth_session WHERE id=?", Integer.class, first.path("sessionId").asText())).isEqualTo(32);
        // 两个并发刷新只能有一个成功，旧访问令牌与刷新令牌随后均失效。
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            var request = Map.of("refreshToken", first.path("refreshToken").asText());
            var a = pool.submit(() -> post("/refresh", request));
            var b = pool.submit(() -> post("/refresh", request));
            var ra=a.get(10,TimeUnit.SECONDS); var rb=b.get(10,TimeUnit.SECONDS);
            assertThat(List.of(ra.getStatusCode().value(), rb.getStatusCode().value())).containsExactlyInAnyOrder(200,401);
        }
        assertThat(me(first).getStatusCode().value()).isEqualTo(401);
        assertThat(post("/logout", Map.of("refreshToken", first.path("refreshToken").asText())).getStatusCode().value()).isEqualTo(401);
        var replaced = login(account, "desktop-a");
        var otherDevice = login(account, "desktop-b");
        var latest = login(account, "desktop-a");
        assertThat(me(replaced).getStatusCode().value()).isEqualTo(401);
        assertThat(me(otherDevice).getStatusCode().value()).isEqualTo(200);
        assertThat(post("/logout", Map.of("refreshToken", latest.path("refreshToken").asText())).getStatusCode().value()).isEqualTo(204);
        assertThat(me(latest).getStatusCode().value()).isEqualTo(401);
        assertThat(post("/logout", Map.of("refreshToken", latest.path("refreshToken").asText())).getStatusCode().value()).isEqualTo(204);
        assertThat(post("/login", Map.of("account", "x", "password", "short", "deviceId", "desktop")).getStatusCode().value()).isEqualTo(400);
    }

    @Test void differentNewUsersCanLoginConcurrentlyWithoutGapLockDeadlock() throws Exception {
        String a=register(), b=register();
        try (ExecutorService pool=Executors.newFixedThreadPool(2)) {
            var start=new CyclicBarrier(2);
            var left=pool.submit(() -> { start.await(5,TimeUnit.SECONDS); return login(a,"fresh-device"); });
            var right=pool.submit(() -> { start.await(5,TimeUnit.SECONDS); return login(b,"fresh-device"); });
            assertThat(left.get(10,TimeUnit.SECONDS).has("accessToken")).isTrue();
            assertThat(right.get(10,TimeUnit.SECONDS).has("accessToken")).isTrue();
        }
    }

    @Test void twoClientsAuthenticateAndRevocationClosesSockets() throws Exception {
        var alice=login(register(), "alice-desktop");
        var bob=login(register(), "bob-desktop");
        String aliceTicket=ticket(alice), bobTicket=ticket(bob);
        try (var client=HttpClient.newHttpClient(); var a=connect(client); var b=connect(client)) {
            a.send(Map.of("v",1,"type","AUTH","ticket",aliceTicket,"userId","forged-user"));
            var accepted=a.next();
            assertThat(accepted.path("type").asText()).isEqualTo("AUTH_OK");
            assertThat(accepted.path("userId").asText()).isEqualTo(alice.path("user").path("id").asText());
            b.send(Map.of("v",1,"type","AUTH","ticket",bobTicket));
            assertThat(b.next().path("type").asText()).isEqualTo("AUTH_OK");
            a.send(Map.of("v",1,"type","PING","requestId","heartbeat-1"));
            assertThat(a.next().path("requestId").asText()).isEqualTo("heartbeat-1");
            a.send(Map.of("v",1,"type","SEND"));
            assertThat(a.next().path("code").asText()).isEqualTo("NOT_IMPLEMENTED");
            // 已消费的票据、过期票据和伪造票据均无法再认证。
            for (String rejected : List.of(aliceTicket, "a".repeat(43))) {
                try(var replay=connect(client)) {
                    replay.send(Map.of("v",1,"type","AUTH","ticket",rejected));
                    assertThat(replay.next().path("code").asText()).isEqualTo("UNAUTHENTICATED");
                    assertThat(replay.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
                }
            }
            String expired=ticket(bob);
            redis.expire("koko:im:ticket:"+HexFormat.of().formatHex(AuthService.digest(expired)),Duration.ZERO);
            try(var stale=connect(client)) {
                stale.send(Map.of("v",1,"type","AUTH","ticket",expired));
                assertThat(stale.next().path("code").asText()).isEqualTo("UNAUTHENTICATED");
            }
            String beforeLogout=ticket(alice);
            post("/logout", Map.of("refreshToken",alice.path("refreshToken").asText()));
            assertThat(a.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
            try(var revoked=connect(client)) {
                revoked.send(Map.of("v",1,"type","AUTH","ticket",beforeLogout));
                assertThat(revoked.next().path("code").asText()).isEqualTo("UNAUTHENTICATED");
            }
            b.send(Map.of("v",1,"type","PING"));
            assertThat(b.next().path("type").asText()).isEqualTo("PONG");
            login(bob.path("user").path("account").asText(),"bob-desktop");
            assertThat(b.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
        }
    }

    private String ticket(JsonNode tokens) {
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(tokens.path("accessToken").asText());
        var response=http.exchange("/api/im/tickets",HttpMethod.POST,new HttpEntity<>(headers),JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().path("ticket").asText();
    }
    private SocketProbe connect(HttpClient client) throws Exception {
        var probe=new SocketProbe();
        probe.socket=client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+netty.port()+"/im"),probe).get(5,TimeUnit.SECONDS);
        return probe;
    }
    private class SocketProbe implements WebSocket.Listener, AutoCloseable {
        WebSocket socket;
        final BlockingQueue<String> messages=new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed=new CompletableFuture<>();
        final StringBuilder fragments=new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            fragments.append(data);
            if(last) { messages.add(fragments.toString()); fragments.setLength(0); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return null; }
        void send(Object value) throws Exception { socket.sendText(json.writeValueAsString(value),true).get(5,TimeUnit.SECONDS); }
        JsonNode next() throws Exception { String value=messages.poll(5,TimeUnit.SECONDS); assertThat(value).isNotNull(); return json.readTree(value); }
        @Override public void close() { socket.abort(); }
    }

    private String register() {
        String account="it_"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        accounts.add(account);
        var response=post("/register", Map.of("account",account,"password",PASSWORD,"nickname","联调用户"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        return account;
    }
    private JsonNode login(String account, String device) {
        var response=post("/login",Map.of("account",account,"password",PASSWORD,"deviceId",device));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }
    private ResponseEntity<JsonNode> post(String path, Object body) { return http.postForEntity("/api/auth"+path,body,JsonNode.class); }
    private ResponseEntity<JsonNode> me(JsonNode tokens) {
        HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(tokens.path("accessToken").asText());
        return http.exchange("/api/auth/me",HttpMethod.GET,new HttpEntity<>(headers),JsonNode.class);
    }
}
