package dev.koko.chat.message.mq;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 短事务认领、事务外发布、CAS 写回；进程退出后依靠租约恢复，允许重复事件。 */
@Component @Profile("local")
public class OutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxMapper mapper;private final ConfirmedPublisher publisher;private final MessagingTopology topology;
    private final TransactionTemplate tx;private final boolean enabled;
    record Claimed(OutboxMapper.Row row,String lease) {}
    public OutboxPublisher(OutboxMapper mapper,ConfirmedPublisher publisher,MessagingTopology topology,
            PlatformTransactionManager transactions,@Value("${koko.messaging.outbox-enabled:true}") boolean enabled) {
        this.mapper=mapper;this.publisher=publisher;this.topology=topology;this.enabled=enabled;
        tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Scheduled(fixedDelayString="${koko.messaging.outbox-delay-ms:500}") public void scheduled() {
        if(!enabled) return;
        try { publishBatch(); } catch(Exception error) { log.warn("Outbox 暂不可用：{}",error.getClass().getSimpleName()); }
    }
    public void publishBatch() {
        var batch=tx.execute(status -> mapper.claimable().stream().map(row -> {
            String lease=UUID.randomUUID().toString();mapper.claim(row.eventId(),lease);return new Claimed(row,lease);
        }).toList());
        for(var item:batch) {
            try {
                publisher.publish(topology.name("message.x"),"message.created",item.row().payload().getBytes(StandardCharsets.UTF_8),item.row().eventId());
                mapper.published(item.row().eventId(),item.lease());
            } catch(Exception error) {
                log.warn("Outbox 发布失败，事件 {} 将重试：{}",item.row().eventId(),error.getClass().getSimpleName());
                mapper.failed(item.row().eventId(),item.lease(),Math.min(30,(item.row().attempts()+1)*2),error.getClass().getSimpleName());
            }
        }
    }
}
