package dev.koko.chat.transport.netty;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/** 用 Spring 生命周期管理 Netty：启动时绑定端口，关闭时释放连接及事件循环。 */
@Component
public class NettyWebSocketServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(NettyWebSocketServer.class);
    private final NettyProperties properties;
    private final ImAuthSupport authentication;
    private final ObjectMapper protocolMapper;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private ChannelGroup connections;
    private volatile Channel listener;

    public NettyWebSocketServer(NettyProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, (ImAuthSupport) null);
    }

    @Autowired
    public NettyWebSocketServer(NettyProperties properties, ObjectMapper objectMapper, ObjectProvider<ImAuthSupport> authentication) {
        this(properties, objectMapper, authentication.getIfAvailable());
    }

    private NettyWebSocketServer(NettyProperties properties, ObjectMapper objectMapper, ImAuthSupport authentication) {
        this.authentication = authentication;
        this.properties = properties;
        // 独立协议解析器不影响 HTTP JSON 配置，并拒绝尾随 JSON 和过深嵌套。
        this.protocolMapper = objectMapper.copy();
        protocolMapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        protocolMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(properties.maxMessageBytes()).build());
    }

    /** 同步启动确保端口绑定失败会阻止应用成功启动，不留下半就绪的接入层。 */
    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        boss = new NioEventLoopGroup(1, new DefaultThreadFactory("koko-im-boss"));
        workers = new NioEventLoopGroup(2, new DefaultThreadFactory("koko-im-worker"));
        // stayClosed=true：关闭期间迟到的新连接也立即关闭，避免遗漏连接。
        connections = new DefaultChannelGroup("koko-im-connections", workers.next(), true);
        ChannelGroup acceptedConnections = connections;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32768, 65536))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            acceptedConnections.add(channel);
                            // 顺序依次为超时、HTTP 解码/聚合、升级检查、WS 握手、分片聚合和业务探针。
                            channel.pipeline()
                                    .addLast(new IdleStateHandler(properties.idleTimeout().toMillis(), 0, 0, TimeUnit.MILLISECONDS))
                                    .addLast(new HttpServerCodec(4096, 8192, 8192))
                                    .addLast(new HttpObjectAggregator(8192))
                                    .addLast(new WebSocketUpgradeGuard(properties.path()))
                                    .addLast(new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                                            .websocketPath(properties.path())
                                            .checkStartsWith(false)
                                            .allowExtensions(false)
                                            .maxFramePayloadLength(properties.maxFrameBytes())
                                            .handshakeTimeoutMillis(10000)
                                            .build()))
                                    .addLast(new WebSocketFrameAggregator(properties.maxMessageBytes()))
                                    .addLast(new WebSocketProbeHandler(protocolMapper, properties, authentication));
                        }
                    });
            // 此等待发生在 Spring 生命周期线程，不在 Netty EventLoop 上阻塞。
            listener = bootstrap.bind(properties.host(), properties.port()).syncUninterruptibly().channel();
            log.info("Netty WebSocket listening on {}:{}{}", properties.host(), port(), properties.path());
        } catch (Exception exception) {
            // Netty 可能直接抛出受检的 BindException，因此不能只捕获 RuntimeException。
            stop();
            throw new IllegalStateException("Cannot bind Netty WebSocket to " + properties.host() + ":" + properties.port(), exception);
        }
    }

    /** 先停止接入，再关闭现有连接，最后退出线程组；重复调用仍安全。 */
    @Override
    public synchronized void stop() {
        Channel current = listener;
        listener = null;
        if (current != null) {
            current.close().awaitUninterruptibly();
        }
        if (connections != null) {
            connections.close().awaitUninterruptibly();
            connections = null;
        }
        shutdown(boss);
        shutdown(workers);
        boss = null;
        workers = null;
    }

    private void shutdown(EventLoopGroup group) {
        if (group != null) {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        Channel current = listener;
        return current != null && current.isActive();
    }

    /** 返回实际绑定端口，便于随机端口测试和系统信息探针共用同一事实。 */
    public int port() {
        Channel current = listener;
        return current == null ? properties.port() : ((InetSocketAddress) current.localAddress()).getPort();
    }

    @Override
    public int getPhase() {
        // 高阶段值让 Netty 在关闭阶段较早停止接入，避免业务资源销毁后仍收新请求。
        return Integer.MAX_VALUE - 100;
    }
}
