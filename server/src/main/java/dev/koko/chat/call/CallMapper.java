package dev.koko.chat.call;

import org.apache.ibatis.annotations.*;
import org.springframework.context.annotation.Profile;
import java.util.List;
import static dev.koko.chat.call.CallModels.*;

/** 用户锁按 ID 排序获得，跨会话的同时呼叫也只能占用同一用户一次。 */
@Mapper @Profile("local")
public interface CallMapper {
    String COLS="id,conversation_id,caller_id,callee_id,caller_session,callee_session,state,reason,expires_at,media_type,caller_camera,callee_camera";
    @Select("SELECT id FROM app_user WHERE id=#{id} FOR UPDATE") Long lockUser(long id);
    @Select("SELECT "+COLS+" FROM call_session WHERE id=#{id} FOR UPDATE") Row lock(String id);
    @Select("SELECT "+COLS+" FROM call_session WHERE id=#{id}") Row find(String id);
    @Select("SELECT "+COLS+" FROM call_session WHERE (caller_id=#{user} OR callee_id=#{user}) AND state<>'ENDED' AND expires_at>UTC_TIMESTAMP(3) ORDER BY created_at DESC LIMIT 1") Row current(long user);
    @Insert("INSERT INTO call_session(id,conversation_id,caller_id,callee_id,caller_session,media_type,expires_at) VALUES(#{id},#{conversationId},#{callerId},#{calleeId},#{callerSession},#{mediaType},DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 45 SECOND))") void create(Row row);
    @Update("UPDATE call_session SET state='CONNECTING',callee_session=#{session},expires_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 45 SECOND),caller_seen=UTC_TIMESTAMP(3),callee_seen=UTC_TIMESTAMP(3) WHERE id=#{id}") void accept(String id,String session);
    @Update("UPDATE call_session SET state='ENDED',caller_camera=FALSE,callee_camera=FALSE,reason=#{reason},ended_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND state<>'ENDED'") void end(String id,String reason);
    @Update("UPDATE call_session SET caller_seen=IF(caller_session=#{session},UTC_TIMESTAMP(3),caller_seen),callee_seen=IF(callee_session=#{session},UTC_TIMESTAMP(3),callee_seen),expires_at=IF(state='ACTIVE',DATE_ADD(LEAST(caller_seen,callee_seen),INTERVAL 30 SECOND),expires_at) WHERE id=#{id}") void heartbeat(String id,String session);
    @Update("UPDATE call_session SET caller_connected=IF(caller_session=#{session},TRUE,caller_connected),callee_connected=IF(callee_session=#{session},TRUE,callee_connected),state=IF(caller_connected AND callee_connected,'ACTIVE',state) WHERE id=#{id}") void connected(String id,String session);
    // 状态只按已绑定 session 更新，重复上报幂等，不占用 SDP/ICE 信令邮箱。
    @Update("UPDATE call_session SET caller_camera=IF(caller_session=#{session},#{enabled},caller_camera),callee_camera=IF(callee_session=#{session},#{enabled},callee_camera) WHERE id=#{id}") void camera(String id,String session,boolean enabled);
    @Select("SELECT id FROM call_session WHERE state<>'ENDED' AND expires_at<=UTC_TIMESTAMP(3) ORDER BY expires_at LIMIT 100") List<String> expired();
    @Select("SELECT COUNT(*) FROM call_session WHERE caller_id=#{user} AND created_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE)") int recentCalls(long user);
    @Select("SELECT COUNT(*) FROM call_signal WHERE call_id=#{call}") int signalCount(String call);
    @Select("SELECT id,signal_id,kind,payload FROM call_signal WHERE call_id=#{call} AND sender_session=#{session} AND signal_id=#{signal}") Signal signal(String call,String session,String signal);
    @Select("SELECT COUNT(*) FROM call_signal WHERE call_id=#{call} AND kind=#{kind}") int sdpCount(String call,String kind);
    @Insert("INSERT INTO call_signal(call_id,sender_session,signal_id,kind,payload) VALUES(#{call},#{session},#{signal},#{kind},#{payload})") void signalInsert(String call,String session,String signal,String kind,String payload);
    @Select("SELECT id,signal_id,kind,payload FROM call_signal WHERE call_id=#{call} AND sender_session<>#{session} AND id>#{after} ORDER BY id LIMIT 64") List<Signal> signals(String call,String session,long after);
    @Delete("DELETE FROM call_signal WHERE call_id=#{call}") void clearSignals(String call);
}
