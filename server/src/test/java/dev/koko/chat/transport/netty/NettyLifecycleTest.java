package dev.koko.chat.transport.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NettyLifecycleTest {
    @Test
    void occupiedPortFailsSpringStartupWithoutLeakingEventLoops() throws Exception {
        Set<Thread> previousThreads = Thread.getAllStackTraces().keySet();
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            new ApplicationContextRunner()
                    .withBean(NettyProperties.class, () -> properties(occupied.getLocalPort(), Duration.ofSeconds(30)))
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(NettyWebSocketServer.class)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(java.net.BindException.class);
                    });
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> !previousThreads.contains(t))
                        .filter(t -> t.getName().startsWith("koko-im-"))
                        .filter(Thread::isAlive)).isEmpty());
    }

    @Test
    void shutdownClosesConnectionsAndReleasesListenerPort() throws Exception {
        NettyWebSocketServer server = new NettyWebSocketServer(properties(0, Duration.ofSeconds(30)), new ObjectMapper());
        server.start();
        int port = server.port();
        try (Socket connection = new Socket()) {
            connection.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            connection.setSoTimeout(3000);
            server.stop();
            assertThat(server.isRunning()).isFalse();
            assertThat(connection.getInputStream().read()).isEqualTo(-1);
            try (ServerSocket rebound = new ServerSocket()) {
                rebound.setReuseAddress(true);
                rebound.bind(new InetSocketAddress("127.0.0.1", port));
                assertThat(rebound.isBound()).isTrue();
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void unauthenticatedConnectionExpiresEvenAfterHandshake() throws Exception {
        NettyWebSocketServer server = new NettyWebSocketServer(properties(0, Duration.ofMillis(150)), new ObjectMapper());
        server.start();
        CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        WebSocket socket = null;
        try (HttpClient client = HttpClient.newHttpClient()) {
            socket = client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/im"),
                    new WebSocket.Listener() {
                        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
                            closeCode.complete(code);
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);
            assertThat(closeCode.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
        } finally {
            if (socket != null) socket.abort();
            server.stop();
        }
    }

    private NettyProperties properties(int port, Duration authenticationTimeout) {
        return new NettyProperties("127.0.0.1", port, "/im", 16384, 16384,
                Duration.ofSeconds(75), authenticationTimeout);
    }
}
