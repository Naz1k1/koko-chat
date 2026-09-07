package dev.koko.chat.system;

import dev.koko.chat.transport.netty.NettyProperties;
import dev.koko.chat.transport.netty.NettyWebSocketServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class SystemInfoService {
    private final Environment environment;
    private final NettyWebSocketServer nettyServer;
    private final NettyProperties properties;
    private final String version;

    public SystemInfoService(Environment environment, NettyWebSocketServer nettyServer,
                             NettyProperties properties, @Value("${koko.version}") String version) {
        this.environment = environment;
        this.nettyServer = nettyServer;
        this.properties = properties;
        this.version = version;
    }

    public SystemInfo info() {
        int httpPort = environment.getProperty("local.server.port", Integer.class,
                environment.getProperty("server.port", Integer.class, 8080));
        return new SystemInfo("koko-chat", version, "skeleton", httpPort, nettyServer.port(), properties.path());
    }
}
