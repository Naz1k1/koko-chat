package dev.koko.chat.message.mq;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** ACK 和 mandatory return 必须关联检查：不可路由时即使 broker ACK 也视为失败。 */
@Component @Profile("local")
public class ConfirmedPublisher {
    private final RabbitTemplate template;
    public ConfirmedPublisher(RabbitTemplate template) { this.template=template; }
    public void publish(String exchange,String key,byte[] body,String eventId) throws Exception {
        var properties=new MessageProperties();properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(eventId);
        var correlation=new CorrelationData(UUID.randomUUID().toString());
        template.send(exchange,key,new Message(body,properties),correlation);
        var confirm=correlation.getFuture().get(3,TimeUnit.SECONDS);
        if(!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("MQ did not confirm a routable publication");
    }
}
