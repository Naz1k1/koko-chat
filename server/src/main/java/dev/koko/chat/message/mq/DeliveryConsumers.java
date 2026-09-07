package dev.koko.chat.message.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dev.koko.chat.auth.AuthModels.Identity;
import dev.koko.chat.message.*;
import dev.koko.chat.message.ChatModels.*;
import dev.koko.chat.transport.netty.ImAuthSupport;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/** 手动 ACK 只在当前责任完成或可靠转交后执行；每个监听线程独占本次 AMQP Channel。 */
@Component @Profile("local")
public class DeliveryConsumers {
    public record GatewayTask(MessageEvent event,OnlineRoutes.Route target,String membershipEpoch) {}
    private final ChatMapper mapper;private final ChatService chat;private final OnlineRoutes routes;
    private final ConfirmedPublisher publisher;private final MessagingTopology topology;private final ImAuthSupport connections;private final ObjectMapper json;
    public DeliveryConsumers(ChatMapper mapper,ChatService chat,OnlineRoutes routes,ConfirmedPublisher publisher,
            MessagingTopology topology,ImAuthSupport connections,ObjectMapper json) {
        this.mapper=mapper;this.chat=chat;this.routes=routes;this.publisher=publisher;this.topology=topology;this.connections=connections;this.json=json;
    }
    @RabbitListener(id="chatDispatch",queues="#{messagingTopology.name('dispatch.q')}")
    public void dispatch(Message raw,Channel channel) throws Exception {
        long tag=raw.getMessageProperties().getDeliveryTag();MessageEvent event;
        try { event=json.readValue(raw.getBody(),MessageEvent.class);validate(event); }
        catch(Exception bad) { transferOrRequeue(raw,channel,tag,null);return; }
        try {
            var message=mapper.message(ChatService.number(event.messageId(),false));
            if(message==null || !event.conversationId().equals(Long.toString(message.conversationId())) || !event.seq().equals(Long.toString(message.seq()))) {
                dead(raw.getBody(),event.eventId());channel.basicAck(tag,false);return;
            }
            for(var member:mapper.members(message.conversationId())) {
                if(message.seq()<member.joinSeq()) continue;
                if(event.recipientUserId()!=null && !event.recipientUserId().equals(Long.toString(member.userId()))) continue;
                for(var route:routes.routes(member.userId())) {
                    if(event.recipientDeviceId()!=null && !event.recipientDeviceId().equals(route.deviceId())) continue;
                    try { publisher.publish(topology.name("gateway.x"),route.gateway(),json.writeValueAsBytes(new GatewayTask(event,route,member.membershipEpoch())),event.eventId()); }
                    catch(Exception failure) { retry(event.retry(Long.toString(route.userId()),route.deviceId())); }
                }
            }
            channel.basicAck(tag,false);
        } catch(Exception failure) { transferOrRequeue(raw,channel,tag,event.retry(event.recipientUserId(),event.recipientDeviceId())); }
    }
    @RabbitListener(id="chatGateway",queues="#{gatewayQueue.name}",concurrency="1")
    public void gateway(Message raw,Channel channel) throws Exception {
        long tag=raw.getMessageProperties().getDeliveryTag();GatewayTask task;
        try { task=json.readValue(raw.getBody(),GatewayTask.class);validate(task.event());if(task.target()==null) throw new IllegalArgumentException(); }
        catch(Exception bad) { transferOrRequeue(raw,channel,tag,null);return; }
        try {
            var target=task.target();var identity=new Identity(target.sessionId(),target.userId(),target.deviceId());
            if(!topology.route().equals(target.gateway()) || !connections.contains(identity) || !connections.active(identity)) { channel.basicAck(tag,false);return; }
            var message=mapper.message(ChatService.number(task.event().messageId(),false));
            if(message==null) { dead(raw.getBody(),task.event().eventId());channel.basicAck(tag,false);return; }
            try {
                var member=chat.member(identity,message.conversationId(),task.membershipEpoch());
                if(message.seq()<member.joinSeq()) { channel.basicAck(tag,false);return; }
            } catch(dev.koko.chat.auth.AuthException stale) { channel.basicAck(tag,false);return; }
            String payload=json.createObjectNode().put("v",1).put("type","MESSAGE").put("membershipEpoch",task.membershipEpoch())
                    .set("message",json.valueToTree(ChatModels.view(message))).toString();
            // 仅等待本地网络写入；不占用 MQ delivery 等待用户设备回执。
            connections.deliver(identity,payload).get(3,TimeUnit.SECONDS);
            channel.basicAck(tag,false);
        } catch(Exception failure) {
            transferOrRequeue(raw,channel,tag,task.event().retry(Long.toString(task.target().userId()),task.target().deviceId()));
        }
    }
    private void validate(MessageEvent event) {
        if(event==null || event.eventVersion()!=1 || !"message.created".equals(event.eventType()) || event.attempt()<0 || event.attempt()>100
                || event.eventId()==null || !event.eventId().matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid event");
        ChatService.number(event.messageId(),false);ChatService.number(event.conversationId(),false);ChatService.number(event.seq(),false);
    }
    private void retry(MessageEvent event) throws Exception {
        byte[] body=json.writeValueAsBytes(event);
        if(event.attempt()>3) dead(body,event.eventId());
        else publisher.publish(topology.name("retry.x"),"retry."+new int[]{5,30,120}[event.attempt()-1]+"s",body,event.eventId());
    }
    private void dead(byte[] body,String event) throws Exception { publisher.publish(topology.name("dead.x"),"dispatch.dead",body,event); }
    private void transferOrRequeue(Message raw,Channel channel,long tag,MessageEvent retry) throws Exception {
        try {
            if(retry==null) dead(raw.getBody(),raw.getMessageProperties().getMessageId()); else retry(retry);
            channel.basicAck(tag,false);
        } catch(Exception unavailable) {
            // 转交失败保留原件；限速重投，持久队列另有 broker delivery-limit 和 DLX 兜底。
            Thread.sleep(1000);channel.basicNack(tag,false,true);
        }
    }
}
