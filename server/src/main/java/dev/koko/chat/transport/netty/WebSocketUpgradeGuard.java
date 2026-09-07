package dev.koko.chat.transport.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;

/** 在握手前校验路径、方法与升级头，明确返回 HTTP 错误，避免无效请求一直等待。 */
final class WebSocketUpgradeGuard extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final String path;

    WebSocketUpgradeGuard(String path) {
        this.path = path;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        HttpResponseStatus status = null;
        if (!request.decoderResult().isSuccess()) {
            status = HttpResponseStatus.BAD_REQUEST;
        } else if (!path.equals(request.uri())) {
            status = HttpResponseStatus.NOT_FOUND;
        } else if (!HttpMethod.GET.equals(request.method())) {
            status = HttpResponseStatus.METHOD_NOT_ALLOWED;
        } else if (!request.headers().contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true)) {
            status = HttpResponseStatus.UPGRADE_REQUIRED;
        }
        if (status == null) {
            // 当前 Handler 会自动释放请求；向后传递前增加引用计数，交给握手 Handler 接管。
            context.fireChannelRead(request.retain());
            return;
        }
        byte[] body = status.reasonPhrase().getBytes(StandardCharsets.UTF_8);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length)
                .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
