package dev.koko.chat.transport.netty;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.koko.chat.auth.AuthException;
import dev.koko.chat.auth.AuthModels.Identity;
import java.util.concurrent.RejectedExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 处理心跳与票据认证；每个连接独享状态，所有数据库及 Redis 操作离开事件循环。 */
final class WebSocketProbeHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private final ObjectMapper mapper;
    private final NettyProperties properties;
    private ScheduledFuture<?> authenticationDeadline;
    private boolean upgraded;
    private final ImAuthSupport authentication;
    private Identity identity;
    private boolean authenticating;
    private boolean checking;
    private boolean closing;
    private ScheduledFuture<?> sessionCheck;

    WebSocketProbeHandler(ObjectMapper mapper, NettyProperties properties, ImAuthSupport authentication) {
        this.authentication = authentication;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 校验 JSON 信封后分派命令；认证成功也不代表消息业务已经实现。 */
    @Override
    protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) {
        if (closing) return;
        if (frame instanceof BinaryWebSocketFrame) {
            close(context, 1003, "Only JSON text messages are supported");
            return;
        }
        if (!(frame instanceof TextWebSocketFrame text)) {
            return;
        }
        try {
            JsonNode envelope = mapper.readTree(text.text());
            if (envelope == null || !envelope.isObject()) {
                error(context, null, "INVALID_MESSAGE", "Expected a JSON object");
                return;
            }
            JsonNode request = envelope.get("requestId");
            if (request != null && (!request.isTextual() || request.textValue().length() > 128)) {
                error(context, null, "INVALID_MESSAGE", "requestId must be a string of at most 128 characters");
                return;
            }
            String requestId = request == null ? null : request.textValue();
            JsonNode version = envelope.get("v");
            if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
                    || version.intValue() != 1) {
                error(context, requestId, "UNSUPPORTED_VERSION", "Only protocol v=1 is supported");
                return;
            }
            JsonNode type = envelope.get("type");
            if (type == null || !type.isTextual()) {
                error(context, requestId, "INVALID_MESSAGE", "type must be a string");
                return;
            }
            switch (type.textValue()) {
                case "PING" -> reply(context, response("PONG", requestId));
                case "AUTH" -> authenticate(context, requestId, envelope);
                case "SEND", "RECEIVED_ACK", "READ" -> error(context, requestId, identity == null ? "UNAUTHENTICATED" : "NOT_IMPLEMENTED", identity == null ? "An authenticated session is required" : "Messaging is not implemented yet");
                default -> error(context, requestId, "NOT_IMPLEMENTED", "Command is not implemented in this skeleton");
            }
        } catch (JsonProcessingException exception) {
            error(context, null, "INVALID_MESSAGE", "Malformed or excessively nested JSON");
        }
    }

    /** 限制每连接一个认证任务，防止重复 AUTH 堆满有界业务队列。 */
    private void authenticate(ChannelHandlerContext context, String requestId, JsonNode envelope) {
        if (authentication == null) { error(context, requestId, "NOT_IMPLEMENTED", "Authentication requires local profile"); return; }
        if (identity != null || authenticating) { error(context, requestId, "AUTH_STATE", "Authentication already started"); return; }
        JsonNode ticket = envelope.get("ticket");
        if (ticket == null || !ticket.isTextual() || ticket.textValue().length() != 43) {
            error(context, requestId, "UNAUTHENTICATED", "Invalid authentication ticket");
            close(context, 1008, "Invalid ticket"); return;
        }
        authenticating = true;
        try {
            authentication.execute(() -> {
                try {
                    Identity result = authentication.authenticate(ticket.textValue());
                    context.executor().execute(() -> {
                        if (closing || !context.channel().isActive()) return;
                        identity = result;
                        authenticating = false;
                        authenticationDeadline.cancel(false);
                        authentication.attach(result, context.channel());
                        reply(context, response("AUTH_OK", requestId).put("userId", Long.toString(result.userId()))
                                .put("deviceId", result.deviceId()).put("sessionId", result.sessionId()));
                        // 定期复查覆盖跨节点注销及认证结果返回前的撤销竞态，不能只依赖本机通知。
                        sessionCheck = context.executor().scheduleWithFixedDelay(() -> checkSession(context), 0, 5, TimeUnit.SECONDS);
                    });
                } catch (Exception failure) {
                    context.executor().execute(() -> {
                        if (closing || !context.channel().isActive()) return;
                        error(context, requestId, failure instanceof AuthException ? "UNAUTHENTICATED" : "SERVICE_UNAVAILABLE", "Unable to authenticate");
                        close(context, failure instanceof AuthException ? 1008 : 1013, "Authentication failed");
                    });
                }
            });
        } catch (RejectedExecutionException busy) {
            error(context, requestId, "BUSY", "Please retry later"); close(context, 1013, "Server busy");
        }
    }

    private void checkSession(ChannelHandlerContext context) {
        if (closing || checking || identity == null) return;
        checking = true;
        try {
            authentication.execute(() -> {
                int code;
                try { code = authentication.active(identity) ? 0 : 1008; }
                catch (Exception unavailable) { code = 1013; }
                int closeCode = code;
                context.executor().execute(() -> {
                    checking = false;
                    if (closeCode != 0 && !closing) close(context, closeCode, "Session unavailable, expired or revoked");
                });
            });
        } catch (RejectedExecutionException busy) { close(context, 1013, "Server busy"); }
    }

    /** 回显请求标识，并使用服务端 UTC 时间；requestId 不承担消息幂等职责。 */
    private ObjectNode response(String type, String requestId) {
        ObjectNode result = mapper.createObjectNode().put("v", 1).put("type", type);
        if (requestId != null) {
            result.put("requestId", requestId);
        }
        return result.put("serverTime", Instant.now().toString());
    }

    private void error(ChannelHandlerContext context, String requestId, String code, String message) {
        reply(context, response("ERROR", requestId).put("code", code).put("message", message));
    }

    private void reply(ChannelHandlerContext context, ObjectNode response) {
        // 写缓冲达到水位时关闭慢连接，避免无限积压待发送响应。
        if (!context.channel().isWritable()) {
            close(context, 1013, "Slow connection; reconnect and retry");
            return;
        }
        context.writeAndFlush(new TextWebSocketFrame(response.toString()));
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            upgraded = true;
            // 心跳不会延长认证期限，只有有效票据认证完成才能取消此任务。
            authenticationDeadline = context.executor().schedule(
                    () -> close(context, 1008, "Authentication required"),
                    properties.authenticationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } else if (event instanceof IdleStateEvent) {
            close(context, 1001, "Connection idle timeout");
        }
        super.userEventTriggered(context, event);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        closing = true;
        if (sessionCheck != null) sessionCheck.cancel(false);
        if (identity != null) authentication.detach(identity, context.channel());
        // 连接关闭后取消定时任务，避免继续引用已经失效的 Channel。
        if (authenticationDeadline != null) {
            authenticationDeadline.cancel(false);
        }
        super.channelInactive(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable exception) {
        if (exception instanceof TooLongFrameException) {
            close(context, 1009, "Message exceeds the configured size limit");
        } else {
            context.close();
        }
    }

    private void close(ChannelHandlerContext context, int code, String reason) {
        if (closing) return;
        closing = true;
        // 握手前仍是 HTTP 连接，不能向其写 WebSocket 关闭帧。
        if (!upgraded) {
            context.close();
            return;
        }
        context.writeAndFlush(new CloseWebSocketFrame(code, reason)).addListener(ChannelFutureListener.CLOSE);
    }
}
