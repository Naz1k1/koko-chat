package dev.koko.chat.message.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.koko.chat.message.ChatMapper;
import dev.koko.chat.message.ChatModels.ReadChanged;
import dev.koko.chat.transport.netty.ImAuthSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 已读状态保存在 MySQL；MQ 只提示在线设备刷新，丢失时通过会话快照恢复。 */
@Component @Profile("local")
public class ReadNotifications {
    public record ReadTask(String type,String conversationId,OnlineRoutes.Route target,String membershipEpoch) {}
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ReadNotifications.class);
    private final ChatMapper mapper;private final OnlineRoutes routes;private final ConfirmedPublisher publisher;
    private final MessagingTopology topology;private final ImAuthSupport connections;private final ObjectMapper json;
    public ReadNotifications(ChatMapper mapper,OnlineRoutes routes,ConfirmedPublisher publisher,MessagingTopology topology,
                             ImAuthSupport connections,ObjectMapper json) {
        this.mapper=mapper;this.routes=routes;this.publisher=publisher;this.topology=topology;this.connections=connections;this.json=json;
    }
    @TransactionalEventListener
    public void changed(ReadChanged event) {
        // 提交后交给有界业务池，不让 broker 故障延迟 READ_ACK，更不在 Netty 事件循环阻塞。
        try { connections.execute(() -> notifyDevices(event)); }
        catch(java.util.concurrent.RejectedExecutionException busy) { log.debug("已读提示延后至快照同步，会话 {}",event.conversationId()); }
    }
    private void notifyDevices(ReadChanged event) {
        try {
            var conversation=mapper.conversation(event.conversationId());var reader=mapper.member(event.conversationId(),event.userId());
            if(conversation==null || !"ACTIVE".equals(conversation.status()) || reader==null || !"ACTIVE".equals(reader.status())
                    || !event.membershipEpoch().equals(reader.membershipEpoch())) return;
            for(var member:mapper.members(event.conversationId())) {
                // 群聊只同步阅读者自己的多设备，不对每条 READ 做全群扇出或宣称全员已读。
                if(!"DIRECT".equals(conversation.type()) && member.userId()!=event.userId()) continue;
                for(var route:routes.routes(member.userId())) {
                    try {
                        var hint=new ReadTask("READ_UPDATE",Long.toString(event.conversationId()),route,member.membershipEpoch());
                        publisher.publish(topology.name("gateway.x"),route.gateway(),json.writeValueAsBytes(hint),java.util.UUID.randomUUID().toString());
                    } catch(Exception unavailable) { log.debug("已读提示未送达，会话 {}，由快照补齐",event.conversationId()); }
                }
            }
        } catch(Exception unavailable) { log.debug("已读路由暂不可用，会话 {}，由快照补齐",event.conversationId()); }
    }
}
