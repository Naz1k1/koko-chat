package dev.koko.chat.call;

import java.time.LocalDateTime;
import java.util.List;

/** 会话编号及信令编号使用 UUID；AUDIO/VIDEO 在创建后固定，摄像头状态只属于已绑定设备。 */
public final class CallModels {
    private CallModels() {}
    public record Row(String id,long conversationId,long callerId,long calleeId,String callerSession,String calleeSession,
                      String state,String reason,LocalDateTime expiresAt,String mediaType,boolean callerCamera,boolean calleeCamera) {}
    public record View(String id,String conversationId,String callerId,String calleeId,String callerSession,String calleeSession,String state,String reason,String expiresAt,String mediaType,boolean callerCamera,boolean calleeCamera) {}
    public record Signal(long id,String signalId,String kind,String payload) {}
    public record Snapshot(View call,List<Signal> signals) {}
    public record Changed(long caller,long callee) {}
    public static View view(Row row) { return new View(row.id(),Long.toString(row.conversationId()),Long.toString(row.callerId()),Long.toString(row.calleeId()),row.callerSession(),row.calleeSession(),row.state(),row.reason(),row.expiresAt().toInstant(java.time.ZoneOffset.UTC).toString(),row.mediaType(),row.callerCamera(),row.calleeCamera()); }
}
