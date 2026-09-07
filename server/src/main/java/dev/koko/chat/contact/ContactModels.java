package dev.koko.chat.contact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 好友接口只返回公开资料；账号凭证与密码哈希不进入联系人 DTO。 */
public final class ContactModels {
    private ContactModels() {}

    public record CreateRequest(
            @NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,32}") String account,
            @Size(max=255) String greeting) {}

    public record RequestRow(long id, long senderId, long receiverId, String greeting, String status,
                             LocalDateTime createdAt, LocalDateTime handledAt) {}
    public record RequestView(String id, String senderId, String receiverId,
                              String senderAccount, String senderNickname, String receiverAccount, String receiverNickname,
                              String greeting, String status, String createdAt, String handledAt) {}
    public record RequestDetails(long id, long senderId, long receiverId,
                                 String senderAccount, String senderNickname, String receiverAccount, String receiverNickname,
                                 String greeting, String status, LocalDateTime createdAt, LocalDateTime handledAt) {
        public RequestView view() {
            return new RequestView(Long.toString(id), Long.toString(senderId), Long.toString(receiverId),
                    senderAccount, senderNickname, receiverAccount, receiverNickname, greeting, status,
                    utc(createdAt), utc(handledAt));
        }
    }
    public record FriendView(String id, String account, String nickname, String remark) {}
    public record RequestPage(List<RequestView> requests, String nextCursor, boolean hasMore) {}
    public record FriendPage(List<FriendView> friends, String nextCursor, boolean hasMore) {}

    private static String utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
