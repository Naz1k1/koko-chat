package dev.koko.chat.transport.netty;

import com.fasterxml.jackson.core.JsonProcessingException;
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

/** 仅处理轻量传输探针；后续阻塞业务必须交给 imBusinessExecutor。每个连接独享一个 Handler。 */
final class WebSocketProbeHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private final ObjectMapper mapper;
    private final NettyProperties properties;
    private ScheduledFuture<?> authenticationDeadline;
    private boolean upgraded;

    WebSocketProbeHandler(ObjectMapper mapper, NettyProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 校验 JSON 信封后分派命令；当前只响应心跳，不伪造认证成功或消息保存确认。 */
    @Override
    protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) {
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
                case "AUTH" -> error(context, requestId, "NOT_IMPLEMENTED", "Authentication is not implemented in this skeleton");
                case "SEND", "RECEIVED_ACK", "READ" -> error(context, requestId, "UNAUTHENTICATED", "An authenticated session is required");
                default -> error(context, requestId, "NOT_IMPLEMENTED", "Command is not implemented in this skeleton");
            }
        } catch (JsonProcessingException exception) {
            error(context, null, "INVALID_MESSAGE", "Malformed or excessively nested JSON");
        }
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
            // 骨架尚无认证会话；心跳不会延长认证期限，防止匿名连接一直占用资源。
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
        // 握手前仍是 HTTP 连接，不能向其写 WebSocket 关闭帧。
        if (!upgraded) {
            context.close();
            return;
        }
        context.writeAndFlush(new CloseWebSocketFrame(code, reason)).addListener(ChannelFutureListener.CLOSE);
    }
}
