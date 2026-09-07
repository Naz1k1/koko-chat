package dev.koko.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.koko.chat.transport.netty.NettyWebSocketServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实随机端口验证 HTTP/WS 协议边界，默认不依赖任何中间件。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "koko.netty.port=0")
@ActiveProfiles("skeleton")
class ServerSmokeTest {
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired NettyWebSocketServer netty;
    @LocalServerPort int httpPort;

    @Test
    void reportsBothActualPortsAndHealthySkeletonWithoutMiddleware() {
        var info = rest.getForEntity("/api/system/info", JsonNode.class);
        assertThat(info.getStatusCode().value()).isEqualTo(200);
        assertThat(info.getBody().path("name").asText()).isEqualTo("koko-chat");
        assertThat(info.getBody().path("version").asText()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(info.getBody().path("stage").asText()).isEqualTo("skeleton");
        assertThat(info.getBody().path("httpPort").asInt()).isEqualTo(httpPort);
        assertThat(info.getBody().path("imPort").asInt()).isEqualTo(netty.port());
        assertThat(info.getBody().path("imPath").asText()).isEqualTo("/im");
        var health = rest.getForEntity("/actuator/health", JsonNode.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
    }

    @Test
    void handlesApplicationAndWebSocketControlPings() throws Exception {
        try (Probe probe = connect()) {
            probe.send("{\"v\":1,\"type\":\"PING\",\"requestId\":\"probe-1\"}");
            JsonNode response = mapper.readTree(probe.nextMessage());
            assertThat(response.path("type").asText()).isEqualTo("PONG");
            assertThat(response.path("requestId").asText()).isEqualTo("probe-1");
            assertThat(response.path("serverTime").asText()).endsWith("Z");
            probe.socket.sendPing(ByteBuffer.wrap("ping".getBytes(StandardCharsets.UTF_8))).get(5, TimeUnit.SECONDS);
            assertThat(probe.pongs.poll(5, TimeUnit.SECONDS)).isEqualTo("ping");
        }
    }

    @Test
    void neverAcknowledgesAuthenticationOrMessagePersistence() throws Exception {
        try (Probe probe = connect()) {
            probe.send("{\"v\":1,\"type\":\"AUTH\",\"requestId\":\"auth-1\",\"ticket\":\"not-a-credential\"}");
            assertError(probe, "NOT_IMPLEMENTED");
            probe.send("{\"v\":1,\"type\":\"SEND\",\"requestId\":\"send-1\",\"body\":{\"text\":\"hello\"}}");
            assertError(probe, "UNAUTHENTICATED");
        }
    }

    @Test
    void rejectsMalformedEnvelopesAndTrailingJson() throws Exception {
        try (Probe probe = connect()) {
            probe.send("not-json");
            assertError(probe, "INVALID_MESSAGE");
            probe.send("{\"v\":2,\"type\":\"PING\"}");
            assertError(probe, "UNSUPPORTED_VERSION");
            probe.send("{\"v\":1,\"type\":\"PING\"} {}");
            assertError(probe, "INVALID_MESSAGE");
        }
    }

    @Test
    void aggregatesFragmentedTextAndLimitsItsTotalSize() throws Exception {
        try (Probe probe = connect()) {
            probe.socket.sendText("{\"v\":1,\"type\":\"PI", false).get(5, TimeUnit.SECONDS);
            probe.socket.sendText("NG\"}", true).get(5, TimeUnit.SECONDS);
            assertThat(mapper.readTree(probe.nextMessage()).path("type").asText()).isEqualTo("PONG");
            probe.socket.sendText("x".repeat(9000), false).get(5, TimeUnit.SECONDS);
            probe.socket.sendText("x".repeat(9000), true).get(5, TimeUnit.SECONDS);
            assertThat(probe.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1009);
        }
    }

    @Test
    void rejectsAnOversizedFrameAndBinaryMessages() throws Exception {
        try (Probe probe = connect()) {
            probe.send("x".repeat(17000));
            assertThat(probe.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1009);
        }
        try (Probe probe = connect()) {
            probe.socket.sendBinary(ByteBuffer.wrap(new byte[]{1, 2}), true).get(5, TimeUnit.SECONDS);
            assertThat(probe.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1003);
        }
    }

    @Test
    void rejectsUnknownUpgradePathsPromptly() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + netty.port() + "/other"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
        }
    }

    private void assertError(Probe probe, String code) throws Exception {
        JsonNode response = mapper.readTree(probe.nextMessage());
        assertThat(response.path("type").asText()).isEqualTo("ERROR");
        assertThat(response.path("code").asText()).isEqualTo(code);
        assertThat(response.has("messageId")).isFalse();
    }

    private Probe connect() throws Exception {
        return new Probe(URI.create("ws://127.0.0.1:" + netty.port() + "/im"));
    }

    static final class Probe implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> pongs = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder fragments = new StringBuilder();
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final WebSocket socket;

        Probe(URI uri) throws Exception {
            socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, this).get(5, TimeUnit.SECONDS);
        }

        void send(String text) throws Exception {
            socket.sendText(text, true).get(5, TimeUnit.SECONDS);
        }

        String nextMessage() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("WebSocket response within five seconds").isNotNull();
            return message;
        }

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                messages.add(fragments.toString());
                fragments.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer data) {
            pongs.add(StandardCharsets.UTF_8.decode(data).toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) { closed.completeExceptionally(error); }

        @Override
        public void close() {
            socket.abort();
            client.close();
        }
    }
}
