package dev.koko.chat.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.koko.chat.message.mq.*;
import dev.koko.chat.transport.netty.ImAuthSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** MQ 只携带唤醒提示；客户端重读权威通话状态，延迟消息不能让已结束通话重新响铃。 */
@Component @Profile("local")
public class CallNotifications {
    public record Task(String type,OnlineRoutes.Route target) {}
    private final ImAuthSupport connections;private final OnlineRoutes routes;private final ConfirmedPublisher publisher;private final MessagingTopology topology;private final ObjectMapper json;
    public CallNotifications(ImAuthSupport connections,OnlineRoutes routes,ConfirmedPublisher publisher,MessagingTopology topology,ObjectMapper json) {
        this.connections=connections;this.routes=routes;this.publisher=publisher;this.topology=topology;this.json=json;
    }
    @TransactionalEventListener public void changed(CallModels.Changed change) {
        try { connections.execute(()->{
            for(long user:new long[]{change.caller(),change.callee()}) try {
                for(var route:routes.routes(user)) publisher.publish(topology.name("gateway.x"),route.gateway(),json.writeValueAsBytes(new Task("CALL_CHANGED",route)),java.util.UUID.randomUUID().toString());
            } catch(Exception ignored) { /* 2 秒状态同步负责补齐，无需重放过期来电。 */ }
        }); } catch(java.util.concurrent.RejectedExecutionException ignored) { /* 保留已提交状态。 */ }
    }
}
