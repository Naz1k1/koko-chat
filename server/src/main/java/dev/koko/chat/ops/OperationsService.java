package dev.koko.chat.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.koko.chat.auth.AuthException;
import dev.koko.chat.message.ChatMapper;
import dev.koko.chat.message.ChatModels.MessageEvent;
import dev.koko.chat.message.mq.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 死信先持久化再 ACK；重放使用独立发布租约，网络写入不占用数据库事务。 */
@Service @Profile("local")
public class OperationsService {
    public record Dead(String id,String firstSeen,String lastSeen,long occurrences,boolean reviewed,boolean replayable,String eventId,String messageId,String problem) {}
    public record Page(List<Dead> items,String nextCursor) {}
    public record Action(String id,String deadId,String kind,String actor,String reason,String status,int attempts,String lastError) {}
    public record Command(String requestId,String reason) {}
    private record Claimed(String id,String lease,String payload,int attempts) {}
    private final JdbcTemplate jdbc;private final ChatMapper chats;private final ObjectMapper json;private final RabbitTemplate rabbit;
    private final ConfirmedPublisher publisher;private final MessagingTopology topology;private final OpsAccess access;private final TransactionTemplate tx;
    private final boolean jobs;private final String actor,scope;
    public OperationsService(JdbcTemplate jdbc,ChatMapper chats,ObjectMapper json,RabbitTemplate rabbit,ConfirmedPublisher publisher,
                             MessagingTopology topology,OpsAccess access,PlatformTransactionManager manager,
                             @Value("${koko.ops.jobs-enabled:true}") boolean jobs,@Value("${KOKO_OPS_ACTOR:local-operator}") String actor) {
        this.jdbc=jdbc;this.chats=chats;this.json=json;this.rabbit=rabbit;this.publisher=publisher;this.topology=topology;this.access=access;this.jobs=jobs;this.actor=actor;
        if(!actor.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid operations actor");
        scope=topology.name("").replaceFirst("\\.$","");tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Scheduled(scheduler="operationsScheduler",fixedDelayString="${koko.ops.work-delay-ms:5000}") public void work() {
        if(!jobs || !access.enabled()) return;
        try { importBatch(); } catch(Exception failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("OPS_ARCHIVE_UNAVAILABLE {}",failure.getClass().getSimpleName()); }
        try { publishBatch(); } catch(Exception failure) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("OPS_WORK_UNAVAILABLE {}",failure.getClass().getSimpleName()); }
    }
    public int importBatch() {
        return rabbit.execute(channel -> {
            int imported=0;
            for(int i=0;i<20;i++) {
                var delivery=channel.basicGet(topology.name("dispatch.dlq"),false);if(delivery==null) break;
                long tag=delivery.getEnvelope().getDeliveryTag();
                try { archive(delivery.getBody());channel.basicAck(tag,false);imported++; }
                catch(Exception failure) { channel.basicNack(tag,false,true);throw new IllegalStateException("Dead letter archive unavailable",failure); }
            }
            return imported;
        });
    }
    String archive(byte[] body) throws Exception {
        if(body.length>262144) throw new IllegalArgumentException("Oversized dead letter retained in broker");
        var digest=MessageDigest.getInstance("SHA-256");digest.update(scope.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);String id=HexFormat.of().formatHex(digest.digest(body));
        jdbc.update("INSERT INTO ops_dead_letter(id,scope,body) VALUES(?,?,?) ON DUPLICATE KEY UPDATE occurrences=occurrences+1,last_seen=UTC_TIMESTAMP(3)",id,scope,body);
        return id;
    }
    public Page list(String after,int limit) {
        if(after!=null && !after.matches("[a-f0-9]{64}")) invalid();if(limit<1 || limit>50) invalid();
        var rows=jdbc.query("SELECT id,body,first_seen,last_seen,occurrences,(reviewed_at IS NOT NULL AND reviewed_at>=last_seen) reviewed FROM ops_dead_letter WHERE scope=? AND id>? ORDER BY id LIMIT ?",(rs,n)-> {
            MessageEvent event=null;try { event=validated(rs.getBytes("body")); } catch(Exception ignored) { /* 不返回原始内容或解析异常。 */ }
            return new Dead(rs.getString("id"),rs.getTimestamp("first_seen").toInstant().toString(),rs.getTimestamp("last_seen").toInstant().toString(),rs.getLong("occurrences"),rs.getBoolean("reviewed"),event!=null,event==null?null:event.eventId(),event==null?null:event.messageId(),event==null?"INVALID_OR_MISSING_MESSAGE":null);
        },scope,after==null?"":after,limit+1);
        return new Page(rows.stream().limit(limit).toList(),rows.size()>limit?rows.get(limit-1).id():null);
    }
    /** 重建消息事件，忽略旧网关地址；保留接收者范围，重新查询当前成员与路由。 */
    private MessageEvent validated(byte[] body) throws Exception {
        var tree=json.readTree(body);var event=json.treeToValue(tree.has("event")?tree.get("event"):tree,MessageEvent.class);
        if(event==null || event.eventVersion()!=1 || !"message.created".equals(event.eventType()) || event.eventId()==null) throw new IllegalArgumentException();
        long id=Long.parseLong(event.messageId());var message=chats.message(id);
        if(message==null || !Long.toString(message.conversationId()).equals(event.conversationId()) || !Long.toString(message.seq()).equals(event.seq())) throw new IllegalArgumentException();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox WHERE event_id=? AND message_id=?",Integer.class,event.eventId(),id)!=1) throw new IllegalArgumentException();
        if(event.recipientUserId()!=null && Long.parseLong(event.recipientUserId())<=0) throw new IllegalArgumentException();
        if(event.recipientDeviceId()!=null && (event.recipientUserId()==null || event.recipientDeviceId().length()>64)) throw new IllegalArgumentException();
        return new MessageEvent(1,"message.created",event.eventId(),event.messageId(),event.conversationId(),event.seq(),0,event.recipientUserId(),event.recipientDeviceId());
    }
    public Action submit(String deadId,String kind,Command command) {
        if(deadId==null || !deadId.matches("[a-f0-9]{64}") || command==null || command.requestId()==null || !command.requestId().matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}") || command.reason()==null || command.reason().isBlank() || command.reason().length()>200 || command.reason().chars().anyMatch(Character::isISOControl)) invalid();
        return tx.execute(status -> {
            var bodies=jdbc.query("SELECT body FROM ops_dead_letter WHERE id=? AND scope=? FOR UPDATE",(rs,n)->rs.getBytes(1),deadId,scope);
            if(bodies.isEmpty()) throw new AuthException(404,"DEAD_LETTER_NOT_FOUND","未找到死信");
            var old=find(command.requestId());
            if(old!=null) { if(!old.deadId().equals(deadId) || !old.kind().equals(kind) || !old.reason().equals(command.reason()) || !old.actor().equals(actor)) throw new AuthException(409,"OPS_COMMAND_CONFLICT","操作编号内容不一致");return old; }
            String payload=null;
            if("REPLAY".equals(kind)) {
                if(jdbc.queryForObject("SELECT COUNT(*) FROM ops_action WHERE active_dead_id=?",Integer.class,deadId)>0) throw new AuthException(409,"REPLAY_PENDING","此死信已有待处理重放");
                try { payload=json.writeValueAsString(validated(bodies.getFirst())); } catch(Exception bad) { throw new AuthException(409,"NOT_REPLAYABLE","事件无效或原消息不存在，不能重放"); }
            } else if(!"ACK".equals(kind)) { invalid(); }
            jdbc.update("INSERT INTO ops_action(id,dead_id,kind,actor,reason,payload,status,completed_at) VALUES(?,?,?,?,?,?,?,IF(?='ACK',UTC_TIMESTAMP(3),NULL))",command.requestId(),deadId,kind,actor,command.reason(),payload,"ACK".equals(kind)?"ACKNOWLEDGED":"PENDING",kind);
            if("ACK".equals(kind)) jdbc.update("UPDATE ops_dead_letter SET reviewed_at=UTC_TIMESTAMP(3) WHERE id=?",deadId);
            return find(command.requestId());
        });
    }
    public Action action(String id) { var result=find(id);if(result==null) throw new AuthException(404,"OPS_ACTION_NOT_FOUND","未找到操作");return result; }
    private Action find(String id) {
        var rows=jdbc.query("SELECT a.* FROM ops_action a JOIN ops_dead_letter d ON a.dead_id=d.id WHERE a.id=? AND d.scope=?",(rs,n)->new Action(rs.getString("id"),rs.getString("dead_id"),rs.getString("kind"),rs.getString("actor"),rs.getString("reason"),rs.getString("status"),rs.getInt("attempts"),rs.getString("last_error")),id,scope);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void publishBatch() {
        var claimed=tx.execute(status -> jdbc.query("SELECT a.id,a.payload,a.attempts FROM ops_action a JOIN ops_dead_letter d ON a.dead_id=d.id WHERE d.scope=? AND ((a.status='PENDING' AND a.next_attempt_at<=UTC_TIMESTAMP(3)) OR (a.status='PUBLISHING' AND a.lease_until<UTC_TIMESTAMP(3))) ORDER BY a.created_at LIMIT 5 FOR UPDATE SKIP LOCKED",(rs,n)->new Claimed(rs.getString(1),UUID.randomUUID().toString(),rs.getString(2),rs.getInt(3)),scope).stream().peek(row -> jdbc.update("UPDATE ops_action SET status='PUBLISHING',lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND),attempts=attempts+1 WHERE id=?",row.lease(),row.id())).toList());
        for(var row:claimed) try {
            var event=json.readValue(row.payload(),MessageEvent.class);
            publisher.publish(topology.name("message.x"),"message.created",row.payload().getBytes(StandardCharsets.UTF_8),event.eventId());
            tx.executeWithoutResult(status -> {
                int updated=jdbc.update("UPDATE ops_action SET status='PUBLISHED',completed_at=UTC_TIMESTAMP(3),lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=? AND status='PUBLISHING' AND lease_token=?",row.id(),row.lease());
                // 只确认操作发起前已观察的故障；此后再次发生的失败仍会报警。
                if(updated==1) jdbc.update("UPDATE ops_dead_letter d JOIN ops_action a ON a.dead_id=d.id SET d.reviewed_at=GREATEST(COALESCE(d.reviewed_at,'1970-01-01'),a.created_at) WHERE a.id=?",row.id());
            });
        } catch(Exception failure) {
            jdbc.update("UPDATE ops_action SET status='PENDING',next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),lease_token=NULL,lease_until=NULL,last_error=? WHERE id=? AND lease_token=? AND status='PUBLISHING'",failure.getClass().getSimpleName(),row.id(),row.lease());
        }
    }
    private static void invalid() { throw new AuthException(400,"INVALID_OPS_COMMAND","请检查操作编号、游标、原因和分页大小"); }
}
