package dev.koko.chat.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;
import java.util.List;

/** 单聊和群聊契约：网络上的 BIGINT 均为十进制字符串，序号以数据库提交顺序为准。 */
public final class ChatModels {
    private ChatModels() {}
    public record DirectRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,32}") String account) {}
    public record ConversationRow(long id, long latestSeq, String status, String type) {}
    public record MemberRow(long conversationId, long userId, String membershipEpoch, long joinSeq, String status, long lastReadSeq) {}
    public record ConversationView(String id, String peerId, String account, String nickname,
            String membershipEpoch, String visibleFromSeq, String latestSeq, String type, String ownerId,
            String lastReadSeq, String unreadCount, String peerLastReadSeq) {}
    public record ConversationPage(List<ConversationView> conversations, String nextCursor, boolean hasMore) {}
    public record MessageRow(long id, long conversationId, long seq, long senderId, String senderMembershipEpoch,
            String clientMsgId, String type, String text, byte[] bodyHash, LocalDateTime serverTime) {}
    public record MessageView(String id, String conversationId, String seq, String senderId, String clientMsgId,
            String type, String text, String serverTime) {}
    public record MessagePage(List<MessageView> messages, String membershipEpoch, String visibleFromSeq,
            String toSeq, String nextCursor, boolean hasMore) {}
    public record SendCommand(String conversationId, String membershipEpoch, String clientMsgId, String text) {}
    public record ReceiptCommand(String conversationId, String membershipEpoch, String receivedSeq) {}
    /** READ 是用户共享进度；接收确认仍属于当前设备。 */
    public record ReadCommand(String conversationId, String membershipEpoch, String readSeq) {}
    public record ReadChanged(long conversationId, long userId, String membershipEpoch) {}
    public record MessageEvent(int eventVersion, String eventType, String eventId, String messageId,
            String conversationId, String seq, int attempt, String recipientUserId, String recipientDeviceId) {
        public MessageEvent(int version,String type,String event,String message,String conversation,String seq,int attempt) {
            this(version,type,event,message,conversation,seq,attempt,null,null);
        }
        public MessageEvent retry(String user,String device) {
            return new MessageEvent(eventVersion,eventType,eventId,messageId,conversationId,seq,attempt+1,user,device);
        }
    }
    public static MessageView view(MessageRow row) {
        return new MessageView(Long.toString(row.id()),Long.toString(row.conversationId()),Long.toString(row.seq()),
                Long.toString(row.senderId()),row.clientMsgId(),row.type(),row.text(),row.serverTime().toInstant(java.time.ZoneOffset.UTC).toString());
    }
}
