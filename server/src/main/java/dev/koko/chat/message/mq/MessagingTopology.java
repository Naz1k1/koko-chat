package dev.koko.chat.message.mq;

import org.springframework.amqp.core.*;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.*;

/** 持久分发、三档重试和死信队列；每个 JVM 独占一个有界在线通知队列。 */
@Configuration(proxyBeanMethods=false) @Profile("local") @EnableScheduling
public class MessagingTopology {
    private final String prefix;
    private final String route="gw."+UUID.randomUUID();
    public MessagingTopology(@Value("${koko.messaging.prefix:im}") String prefix) {
        if(!prefix.matches("[a-z0-9.-]{1,48}")) throw new IllegalArgumentException("Invalid MQ prefix");this.prefix=prefix;
    }
    public String name(String suffix) { return prefix+"."+suffix; }
    public String route() { return route; }
    public String gatewayQueueName() { return name(route); }
    @Bean public Queue gatewayQueue() {
        return new Queue(gatewayQueueName(),false,true,true,Map.of("x-message-ttl",30000,"x-max-length",5000,"x-overflow","reject-publish"));
    }
    @Bean public Declarables messagingDeclarations() {
        List<Declarable> declarations=new ArrayList<>();
        var events=new TopicExchange(name("message.x"));var gateway=new DirectExchange(name("gateway.x"));
        var retry=new DirectExchange(name("retry.x"));var dead=new DirectExchange(name("dead.x"));
        Collections.addAll(declarations,events,gateway,retry,dead);
        Queue dispatch=QueueBuilder.durable(name("dispatch.q")).quorum().maxLength(10000).overflow(QueueBuilder.Overflow.rejectPublish)
                .deadLetterExchange(dead.getName()).deadLetterRoutingKey("dispatch.dead")
                .withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-delivery-limit",5).build();
        Queue dlq=QueueBuilder.durable(name("dispatch.dlq")).quorum().build();
        Collections.addAll(declarations,dispatch,dlq,BindingBuilder.bind(dispatch).to(events).with("message.created"),
                BindingBuilder.bind(dlq).to(dead).with("dispatch.dead"),BindingBuilder.bind(gatewayQueue()).to(gateway).with(route));
        for(int seconds:new int[]{5,30,120}) {
            Queue queue=QueueBuilder.durable(name("dispatch.retry."+seconds+"s.q")).quorum().ttl(seconds*1000).maxLength(10000)
                    .overflow(QueueBuilder.Overflow.rejectPublish).deadLetterExchange(events.getName()).deadLetterRoutingKey("message.created")
                    .withArgument("x-dead-letter-strategy","at-least-once").build();
            Collections.addAll(declarations,queue,BindingBuilder.bind(queue).to(retry).with("retry."+seconds+"s"));
        }
        return new Declarables(declarations);
    }
}
