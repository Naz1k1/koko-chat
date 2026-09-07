package dev.koko.chat.auth;

import com.fasterxml.jackson.databind.JsonNode;
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
