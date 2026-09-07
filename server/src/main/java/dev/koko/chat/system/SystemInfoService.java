package dev.koko.chat.system;

import dev.koko.chat.transport.netty.NettyProperties;
import dev.koko.chat.transport.netty.NettyWebSocketServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** 汇总应用版本与 HTTP、Netty 运行信息，隔离 Controller 与底层连接实现。 */
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
        // 测试可使用随机端口，优先读取启动完成后发布的实际端口。
        int httpPort = environment.getProperty("local.server.port", Integer.class,
                environment.getProperty("server.port", Integer.class, 8080));
        String stage = environment.matchesProfiles("local") ? "groups" : "skeleton";
        return new SystemInfo("koko-chat", version, stage, httpPort, nettyServer.port(), properties.path());
    }
}
