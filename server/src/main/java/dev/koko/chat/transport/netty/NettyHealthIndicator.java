package dev.koko.chat.transport.netty;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("netty")
public class NettyHealthIndicator implements HealthIndicator {
    private final NettyWebSocketServer server;

    public NettyHealthIndicator(NettyWebSocketServer server) {
        this.server = server;
    }

    @Override
    public Health health() {
        return (server.isRunning() ? Health.up() : Health.down()).build();
    }
}
