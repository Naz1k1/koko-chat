package dev.koko.chat.call;

import com.fasterxml.jackson.databind.JsonNode;
import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.Identity;
import dev.koko.chat.message.*;
import org.springframework.context.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static dev.koko.chat.call.CallModels.*;

/** 通话命令经认证 Netty 执行；状态机与信令邮箱同事务，音视频字节由 WebRTC 传输。 */
@Service @Profile("local")
public class CallService {
    private final CallMapper mapper;private final ChatMapper chats;private final AuthService auth;private final ApplicationEventPublisher events;private final TransactionTemplate tx;
    public CallService(CallMapper mapper,ChatMapper chats,AuthService auth,ApplicationEventPublisher events,PlatformTransactionManager manager) {
        this.mapper=mapper;this.chats=chats;this.auth=auth;this.events=events;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Snapshot command(Identity identity,JsonNode body) {
        if(!auth.active(identity)) throw AuthException.unauthorized();
        String action=field(body,"action"),id=field(body,"callId");
        if("CREATE".equals(action)) return create(identity,body);
        if(id==null && "SYNC".equals(action)) { var current=mapper.current(identity.userId());if(current==null) return new Snapshot(null,List.of());id=current.id(); }
        validId(id);String callId=id;
        return tx.execute(status -> {
            var row=mapper.lock(callId);authorize(identity,row);
            row=expire(row);
            if("SYNC".equals(action)) {
                if(!device(identity,row)) return new Snapshot(null,List.of());
                if(!"ENDED".equals(row.state())) mapper.heartbeat(callId,identity.sessionId());
                long after=number(body,"after");return snapshot(mapper.find(callId),identity,after);
            }
            if("ENDED".equals(row.state())) return snapshot(row,identity,0);
            if("ACCEPT".equals(action)) {
                if(identity.userId()!=row.calleeId()) denied();
                if("RINGING".equals(row.state())) { mapper.accept(callId,identity.sessionId());changed(row); }
                else if(!identity.sessionId().equals(row.calleeSession())) throw new AuthException(409,"CALL_ANSWERED_ELSEWHERE","已在其他设备接听");
            } else if("END".equals(action)) {
                if(!device(identity,row)) denied();
                end(row,"RINGING".equals(row.state())?(identity.userId()==row.callerId()?"CANCELLED":"REJECTED"):"HANGUP");
            } else if("CONNECTED".equals(action)) {
                if(!device(identity,row) || "RINGING".equals(row.state())) denied();
                mapper.connected(callId,identity.sessionId());mapper.heartbeat(callId,identity.sessionId());changed(row);
            } else if("MEDIA".equals(action)) {
                if(!device(identity,row) || "RINGING".equals(row.state()) || !"VIDEO".equals(row.mediaType())) denied();
                var enabled=body.get("cameraEnabled");if(enabled==null || !enabled.isBoolean()) invalid();
                mapper.camera(callId,identity.sessionId(),enabled.booleanValue());changed(row);
            } else if("SIGNAL".equals(action)) {
                if(!device(identity,row) || "RINGING".equals(row.state())) denied();
                String signal=field(body,"signalId"),kind=field(body,"kind"),payload=field(body,"payload");validId(signal);
                if(!Set.of("OFFER","ANSWER","ICE").contains(Objects.toString(kind,"")) || payload==null || payload.isBlank() || payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>12288) invalid();
                if(("OFFER".equals(kind) && identity.userId()!=row.callerId()) || ("ANSWER".equals(kind) && identity.userId()!=row.calleeId())) denied();
                var previous=mapper.signal(callId,identity.sessionId(),signal);
                if(previous!=null) { if(!previous.kind().equals(kind) || !previous.payload().equals(payload)) throw new AuthException(409,"SIGNAL_CONFLICT","同一编号信令内容不一致"); }
                else {
                    if(mapper.signalCount(callId)>=256) throw new AuthException(429,"SIGNAL_LIMIT","本次通话信令过多，请重新拨打");
                    if(!"ICE".equals(kind) && mapper.sdpCount(callId,kind)>0) throw new AuthException(409,"SDP_ALREADY_SET","本轮协商描述已确定");
                    mapper.signalInsert(callId,identity.sessionId(),signal,kind,payload);
                }
            } else invalid();
            return snapshot(mapper.find(callId),identity,0);
        });
    }
    private Snapshot create(Identity identity,JsonNode body) {
        String id=field(body,"callId");validId(id);long conversation=ChatService.number(field(body,"conversationId"),false);
        String mediaType=body.has("mediaType")?field(body,"mediaType"):"AUDIO";
        if(!Set.of("AUDIO","VIDEO").contains(Objects.toString(mediaType,""))) invalid();
        return tx.execute(status -> {
            var chat=chats.conversation(conversation);var me=chats.member(conversation,identity.userId());
            if(chat==null || !"DIRECT".equals(chat.type()) || !"ACTIVE".equals(chat.status()) || me==null || !"ACTIVE".equals(me.status()) || !me.membershipEpoch().equals(field(body,"membershipEpoch"))) denied();
            var peer=chats.members(conversation).stream().filter(m->m.userId()!=identity.userId()).findFirst().orElseThrow(()->new AuthException(403,"CALL_FORBIDDEN","没有可呼叫的成员"));
            mapper.lockUser(Math.min(identity.userId(),peer.userId()));mapper.lockUser(Math.max(identity.userId(),peer.userId()));
            var old=mapper.find(id);
            if(old!=null) {
                if(old.callerId()!=identity.userId() || !old.callerSession().equals(identity.sessionId()) || old.conversationId()!=conversation || !old.mediaType().equals(mediaType)) throw new AuthException(409,"CALL_CONFLICT","呼叫编号已被使用");
                return snapshot(old,identity,0);
            }
            if(mapper.recentCalls(identity.userId())>=10) throw new AuthException(429,"CALL_RATE_LIMIT","呼叫过于频繁，请稍后再试");
            if(mapper.current(identity.userId())!=null || mapper.current(peer.userId())!=null) throw new AuthException(409,"CALL_BUSY","自己或对方正在通话");
            var row=new Row(id,conversation,identity.userId(),peer.userId(),identity.sessionId(),null,"RINGING",null,LocalDateTime.now(ZoneOffset.UTC).plusSeconds(45),mediaType,false,false);
            mapper.create(row);changed(row);return snapshot(mapper.find(id),identity,0);
        });
    }
    private Snapshot snapshot(Row row,Identity identity,long after) {
        // 一个快照最多携带 12 KiB 原始信令，避免音视频 SDP 堆积撑爆客户端帧限制。
        List<Signal> signals=new ArrayList<>();int bytes=0;
        if(!"ENDED".equals(row.state()) && device(identity,row)) for(var signal:mapper.signals(row.id(),identity.sessionId(),after)) {
            int size=signal.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(bytes+size>12288) break;signals.add(signal);bytes+=size;
        }
        return new Snapshot(view(row),signals);
    }
    private boolean device(Identity identity,Row row) {
        return identity.userId()==row.callerId()?identity.sessionId().equals(row.callerSession()):row.calleeSession()==null || identity.sessionId().equals(row.calleeSession());
    }
    private void authorize(Identity identity,Row row) { if(row==null || (identity.userId()!=row.callerId() && identity.userId()!=row.calleeId())) denied(); }
    private Row expire(Row row) { if(!"ENDED".equals(row.state()) && !row.expiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {end(row,"TIMEOUT");return mapper.find(row.id());}return row; }
    private void end(Row row,String reason) { mapper.end(row.id(),reason);mapper.clearSignals(row.id());changed(row); }
    private void changed(Row row) { events.publishEvent(new Changed(row.callerId(),row.calleeId())); }
    @Scheduled(fixedDelay=2000) public void expireCalls() { for(String id:mapper.expired()) tx.executeWithoutResult(s -> { var row=mapper.lock(id);if(row!=null) expire(row); }); }
    private static String field(JsonNode body,String name) { var value=body.get(name);return value!=null && value.isTextual()?value.asText():null; }
    private static long number(JsonNode body,String name) { var value=body.get(name);if(value==null) return 0;if(!value.canConvertToLong() || value.asLong()<0) invalid();return value.asLong(); }
    private static void validId(String id) { if(id==null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) invalid(); }
    private static void invalid() { throw new AuthException(400,"INVALID_CALL","通话参数不合法"); }
    private static void denied() { throw new AuthException(403,"CALL_FORBIDDEN","当前会话或设备无权操作此通话"); }
}
