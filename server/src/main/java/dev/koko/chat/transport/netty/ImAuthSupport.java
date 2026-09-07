package dev.koko.chat.transport.netty;

import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.Identity;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.concurrent.ConcurrentHashMap;

/** Netty 与认证业务的适配层：阻塞任务使用业务线程池，Channel 仅存在当前 JVM。 */
@Component
@Profile("local")
public class ImAuthSupport {
    private final ImTicketService tickets;
    private final AuthService auth;
    private final TaskExecutor executor;
    private final ConcurrentHashMap<String,Channel> channels=new ConcurrentHashMap<>();
    public ImAuthSupport(ImTicketService tickets, AuthService auth, @Qualifier("imBusinessExecutor") TaskExecutor executor) {
        this.tickets=tickets;this.auth=auth;this.executor=executor;
    }
    public void execute(Runnable task) { executor.execute(task); }
    public Identity authenticate(String ticket) { return tickets.consume(ticket); }
    public boolean active(Identity identity) { return auth.active(identity); }
    public void attach(Identity identity, Channel channel) {
        Channel previous=channels.put(identity.sessionId(),channel);
        if (previous!=null && previous!=channel) revoke(previous);
    }
    public void detach(Identity identity, Channel channel) { channels.remove(identity.sessionId(),channel); }
    @TransactionalEventListener
    public void revoked(AuthService.SessionsRevoked event) {
        // 只在数据库事务提交后关闭连接；事务回滚不能误踢仍然有效的客户端。
        event.sessionIds().forEach(id -> { Channel channel=channels.remove(id); if(channel!=null) revoke(channel); });
    }
    private void revoke(Channel channel) {
        channel.eventLoop().execute(() -> {
            if(channel.isActive()) channel.writeAndFlush(new CloseWebSocketFrame(1008,"Session revoked or replaced")).addListener(ChannelFutureListener.CLOSE);
        });
    }
}
