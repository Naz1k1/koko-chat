package dev.koko.chat.call;

import java.time.LocalDateTime;
import java.util.List;

/** 会话编号及信令编号使用 UUID；只支持 AUDIO，视频待完成设备与渲染验证后扩展。 */
public final class CallModels {
    private CallModels() {}
    public record Row(String id,long conversationId,long callerId,long calleeId,String callerSession,String calleeSession,
                      String state,String reason,LocalDateTime expiresAt) {}
    public record View(String id,String conversationId,String callerId,String calleeId,String callerSession,String calleeSession,String state,String reason,String expiresAt) {}
    public record Signal(long id,String signalId,String kind,String payload) {}
    public record Snapshot(View call,List<Signal> signals) {}
    public record Changed(long caller,long callee) {}
    public static View view(Row row) { return new View(row.id(),Long.toString(row.conversationId()),Long.toString(row.callerId()),Long.toString(row.calleeId()),row.callerSession(),row.calleeSession(),row.state(),row.reason(),row.expiresAt().toInstant(java.time.ZoneOffset.UTC).toString()); }
}
