package dev.koko.chat.transport.netty;

import com.fasterxml.jackson.core.StreamReadConstraints;
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

@Component
public class NettyWebSocketServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(NettyWebSocketServer.class);
    private final NettyProperties properties;
    private final ObjectMapper protocolMapper;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private ChannelGroup connections;
    private volatile Channel listener;

    public NettyWebSocketServer(NettyProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.protocolMapper = objectMapper.copy();
        protocolMapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        protocolMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(properties.maxMessageBytes()).build());
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        boss = new NioEventLoopGroup(1, new DefaultThreadFactory("koko-im-boss"));
        workers = new NioEventLoopGroup(2, new DefaultThreadFactory("koko-im-worker"));
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
                                    .addLast(new WebSocketProbeHandler(protocolMapper, properties));
                        }
                    });
            // Startup runs on Spring's lifecycle thread, never on an EventLoop.
            listener = bootstrap.bind(properties.host(), properties.port()).syncUninterruptibly().channel();
            log.info("Netty WebSocket listening on {}:{}{}", properties.host(), port(), properties.path());
        } catch (Exception exception) {
            stop();
            throw new IllegalStateException("Cannot bind Netty WebSocket to " + properties.host() + ":" + properties.port(), exception);
        }
    }

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

    public int port() {
        Channel current = listener;
        return current == null ? properties.port() : ((InetSocketAddress) current.localAddress()).getPort();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
