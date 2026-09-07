package dev.koko.chat.group;

import java.util.List;

/** 群规模最多 200 人；成员周期用于拒绝退出重入后迟到的旧操作。 */
public final class GroupModels {
    private GroupModels() {}
    public record Create(String clientCommandId, String title, List<String> memberIds) {}
    public record Invite(String clientCommandId, String membershipEpoch, List<String> memberIds) {}
    public record Change(String clientCommandId, String membershipEpoch, String targetEpoch) {}
    public record Result(String groupId, String clientCommandId) {}
    public record GroupRow(long id, String title, long ownerId, long latestSeq, String status) {}
    public record CommandRow(byte[] requestHash, long conversationId) {}
    public record Member(String userId, String account, String nickname, String role, String membershipEpoch) {}
    public record Detail(String id, String title, String ownerId, String membershipEpoch, List<Member> members) {}
}
