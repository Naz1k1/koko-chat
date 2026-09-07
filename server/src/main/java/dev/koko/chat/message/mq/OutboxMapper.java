package dev.koko.chat.message.mq;

import org.apache.ibatis.annotations.*;
import org.springframework.context.annotation.Profile;
import java.util.List;

/** 租约认领及结果写回必须带认领令牌，过期工作者不能覆盖后来的发布结果。 */
@Mapper @Profile("local")
public interface OutboxMapper {
    record Row(String eventId,String payload,int attempts) {}
    @Select("""
        SELECT event_id,payload,attempts FROM message_outbox
        WHERE (status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3)) OR (status='PUBLISHING' AND lease_until<UTC_TIMESTAMP(3))
        ORDER BY created_at,event_id LIMIT 5 FOR UPDATE SKIP LOCKED
        """) List<Row> claimable();
    @Update("UPDATE message_outbox SET status='PUBLISHING',lease_token=#{lease},lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND),attempts=attempts+1 WHERE event_id=#{event}")
    void claim(String event,String lease);
    @Update("UPDATE message_outbox SET status='PUBLISHED',published_at=UTC_TIMESTAMP(3),lease_token=NULL,lease_until=NULL,last_error=NULL WHERE event_id=#{event} AND lease_token=#{lease} AND status='PUBLISHING'")
    int published(String event,String lease);
    @Update("UPDATE message_outbox SET status='PENDING',lease_token=NULL,lease_until=NULL,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL #{delay} SECOND),last_error=#{error} WHERE event_id=#{event} AND lease_token=#{lease} AND status='PUBLISHING'")
    int failed(String event,String lease,int delay,String error);
}
