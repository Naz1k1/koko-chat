package dev.koko.chat.ops;

import dev.koko.chat.message.mq.MessagingTopology;
import dev.koko.chat.transport.netty.ImAuthSupport;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** 定时采集缓存快照，HTTP 抓取不触发全表统计；告警只在触发或恢复时记日志。 */
@Component @Profile("local")
public class OperationsMonitor {
    public record Snapshot(Instant collectedAt,Map<String,Double> metrics,Set<String> alerts) {}
    private volatile Snapshot snapshot=new Snapshot(Instant.EPOCH,Map.of(),Set.of("SNAPSHOT_UNAVAILABLE"));
    private final JdbcTemplate jdbc;private final StringRedisTemplate redis;private final org.springframework.amqp.core.AmqpAdmin rabbit;private final MessagingTopology topology;
    private final dev.koko.chat.attachment.RustFsStorage storage;
    private final ImAuthSupport connections;private final ThreadPoolTaskExecutor executor;private final OpsAccess access;private final boolean jobs;
    public OperationsMonitor(JdbcTemplate jdbc,StringRedisTemplate redis,org.springframework.amqp.core.AmqpAdmin rabbit,MessagingTopology topology,ImAuthSupport connections,
                             @Qualifier("imBusinessExecutor") ThreadPoolTaskExecutor executor,OpsAccess access,dev.koko.chat.attachment.RustFsStorage storage,@Value("${koko.ops.jobs-enabled:true}") boolean jobs) {
        this.jdbc=jdbc;this.redis=redis;this.rabbit=rabbit;this.topology=topology;this.connections=connections;this.executor=executor;this.access=access;this.storage=storage;this.jobs=jobs;
    }
    @Scheduled(scheduler="operationsScheduler",fixedDelayString="${koko.ops.monitor-delay-ms:15000}") public void scheduled() { if(jobs && access.enabled()) refresh(); }
    public synchronized Snapshot refresh() {
        var metrics=new TreeMap<String,Double>();String scope=topology.name("").replaceFirst("\\.$","");
        metrics.put("connections",(double)connections.connectionCount());
        metrics.put("business_active",(double)executor.getActiveCount());metrics.put("business_queued",(double)executor.getThreadPoolExecutor().getQueue().size());
        try {
            metrics.put("outbox_pending",jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE status<>'PUBLISHED'",Double.class));
            metrics.put("outbox_oldest_seconds",jdbc.queryForObject("SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP())),0) FROM message_outbox WHERE status<>'PUBLISHED'",Double.class));
            metrics.put("dead_unreviewed",jdbc.queryForObject("SELECT COUNT(*) FROM ops_dead_letter WHERE scope=? AND (reviewed_at IS NULL OR reviewed_at<last_seen)",Double.class,scope));
            metrics.put("replay_oldest_seconds",jdbc.queryForObject("SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,a.created_at,UTC_TIMESTAMP())),0) FROM ops_action a JOIN ops_dead_letter d ON a.dead_id=d.id WHERE d.scope=? AND a.status IN ('PENDING','PUBLISHING')",Double.class,scope));
            metrics.put("mysql_up",1d);
        } catch(Exception unavailable) { metrics.put("mysql_up",0d); }
        try { metrics.put("redis_up","PONG".equals(redis.execute((org.springframework.data.redis.core.RedisCallback<String>)connection -> connection.ping()))?1d:0d); }
        catch(Exception unavailable) { metrics.put("redis_up",0d); }
        try {
            for(String suffix:List.of("dispatch.q","dispatch.dlq","dispatch.retry.5s.q","dispatch.retry.30s.q","dispatch.retry.120s.q")) {
                var properties=rabbit.getQueueProperties(topology.name(suffix));if(properties==null) throw new IllegalStateException("Missing queue");
                metrics.put("queue_"+suffix.replace('.','_')+"_ready",((Number)properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).doubleValue());
            }
            metrics.put("rabbitmq_up",1d);
        } catch(Exception unavailable) { metrics.put("rabbitmq_up",0d); }
        try { metrics.put("rustfs_up",storage.healthy()?1d:0d); } catch(Exception unavailable) { metrics.put("rustfs_up",0d); }
        var alerts=evaluate(metrics);var log=org.slf4j.LoggerFactory.getLogger(getClass());
        alerts.stream().filter(a->!snapshot.alerts().contains(a)).forEach(a->log.warn("OPS_ALERT_FIRING {}",a));
        snapshot.alerts().stream().filter(a->!alerts.contains(a)).forEach(a->log.info("OPS_ALERT_RESOLVED {}",a));
        snapshot=new Snapshot(Instant.now(),Map.copyOf(metrics),Set.copyOf(alerts));return snapshot;
    }
    static Set<String> evaluate(Map<String,Double> m) {
        var alerts=new TreeSet<String>();
        for(String dependency:List.of("mysql","redis","rabbitmq","rustfs")) if(m.getOrDefault(dependency+"_up",0d)!=1d) alerts.add(dependency.toUpperCase(Locale.ROOT)+"_UNAVAILABLE");
        if(m.getOrDefault("outbox_oldest_seconds",0d)>=60 || m.getOrDefault("outbox_pending",0d)>=100) alerts.add("OUTBOX_BACKLOG");
        if(m.getOrDefault("queue_dispatch_dlq_ready",0d)>0 || m.getOrDefault("dead_unreviewed",0d)>0) alerts.add("DEAD_LETTERS_PENDING");
        if(m.getOrDefault("queue_dispatch_q_ready",0d)>=1000) alerts.add("DISPATCH_BACKLOG");
        if(m.getOrDefault("business_queued",0d)>=200) alerts.add("BUSINESS_POOL_PRESSURE");
        if(m.getOrDefault("replay_oldest_seconds",0d)>=60) alerts.add("REPLAY_BACKLOG");
        return alerts;
    }
    public Snapshot snapshot() { return snapshot; }
    public String prometheus() {
        var current=snapshot;var output=new StringBuilder();
        current.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> output.append("# HELP koko_").append(entry.getKey()).append(" Operational snapshot gauge.\n# TYPE koko_").append(entry.getKey()).append(" gauge\nkoko_").append(entry.getKey()).append(' ').append(entry.getValue()).append('\n'));
        output.append("# HELP koko_snapshot_age_seconds Seconds since the last successful monitor cycle.\n# TYPE koko_snapshot_age_seconds gauge\nkoko_snapshot_age_seconds ").append(Math.max(0,Instant.now().getEpochSecond()-current.collectedAt().getEpochSecond())).append('\n');
        return output.toString();
    }
}
