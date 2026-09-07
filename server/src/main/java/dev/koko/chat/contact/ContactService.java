package dev.koko.chat.contact;

import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.Identity;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.SecureRandom;
import java.util.*;
import static dev.koko.chat.contact.ContactModels.*;

/** 常规业务服务：同一用户对串行处理，双向关系和申请状态必须在一个事务提交。 */
@Service @Profile("local")
public class ContactService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ContactMapper mapper;
    private final AuthMapper users;
    private final AuthService auth;
    private final TransactionTemplate tx;

    public ContactService(ContactMapper mapper, AuthMapper users, AuthService auth, PlatformTransactionManager transactions) {
        this.mapper = mapper; this.users = users; this.auth = auth;
        tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public RequestView create(Identity identity, CreateRequest body) {
        active(identity);
        if (body == null || body.account() == null || !body.account().matches("[A-Za-z0-9_]{3,32}")
                || (body.greeting() != null && body.greeting().length() > 255)) throw invalid();
        var peer = users.userByAccount(body.account().toLowerCase(Locale.ROOT));
        if (peer == null || !peer.status().equals("ACTIVE")) throw missingUser();
        if (peer.id() == identity.userId()) throw new AuthException(400, "SELF_REQUEST", "不能添加自己为好友");
        String pair = Math.min(peer.id(), identity.userId()) + ":" + Math.max(peer.id(), identity.userId());
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return tx.execute(status -> {
                    lockPair(identity.userId(), peer.id(), true);
                    active(identity);
                    if (mapper.friends(identity.userId(), peer.id()) > 0)
                        throw new AuthException(409, "ALREADY_FRIENDS", "你们已经是好友");
                    var pending = mapper.pending(pair);
                    if (pending != null) {
                        // 响应丢失后重试返回原申请，不能悄悄覆盖已发出的附言。
                        if (pending.senderId() == identity.userId()) return mapper.details(pending.id(), identity.userId()).view();
                        throw new AuthException(409, "INCOMING_REQUEST_PENDING", "对方已向你发出申请，请在收到的申请中处理");
                    }
                    long id = RANDOM.nextLong(1, Long.MAX_VALUE);
                    mapper.insertRequest(id, identity.userId(), peer.id(), body.greeting() == null ? "" : body.greeting().strip());
                    return mapper.details(id, identity.userId()).view();
                });
            } catch (DuplicateKeyException collision) { /* ID 冲突时整笔事务回滚后重试；数据库同时守住待处理用户对唯一性。 */ }
        }
        throw new AuthException(503, "BUSY", "申请暂未完成，请稍后重试");
    }

    public RequestView handle(Identity identity, String requestId, boolean accept) {
        active(identity);
        long id = number(requestId, false);
        var existing = mapper.request(id);
        // 对发起人和无关用户统一返回 404，不暴露他们无权处理的申请资料。
        if (existing == null || existing.receiverId() != identity.userId()) throw missingRequest();
        return tx.execute(status -> {
            lockPair(existing.senderId(), existing.receiverId(), accept);
            active(identity);
            var request = mapper.lockRequest(id);
            String decision = accept ? "ACCEPTED" : "REJECTED";
            if (request.status().equals(decision)) return mapper.details(id, identity.userId()).view();
            if (!request.status().equals("PENDING"))
                throw new AuthException(409, "REQUEST_HANDLED", "申请已处理，请刷新列表");
            if (accept) {
                mapper.insertFriend(request.senderId(), request.receiverId());
                mapper.insertFriend(request.receiverId(), request.senderId());
            }
            if (mapper.handle(id, decision) != 1) throw new IllegalStateException("申请状态更新失败");
            return mapper.details(id, identity.userId()).view();
        });
    }

    public FriendPage friends(Identity identity, String afterId, int limit) {
        active(identity); checkLimit(limit);
        var rows = mapper.list(identity.userId(), number(afterId, true), limit + 1);
        var page = rows.stream().limit(limit).toList();
        return new FriendPage(page, page.isEmpty() ? afterId : page.getLast().id(), rows.size() > limit);
    }

    public RequestPage requests(Identity identity, String afterId, int limit, String direction, String status) {
        active(identity); checkLimit(limit);
        if (!Set.of("all", "incoming", "outgoing").contains(direction)
                || !Set.of("ALL", "PENDING", "ACCEPTED", "REJECTED", "CANCELLED").contains(status)) throw invalid();
        var rows = mapper.requests(identity.userId(), number(afterId, true), direction, status, limit + 1);
        var page = rows.stream().limit(limit).map(RequestDetails::view).toList();
        return new RequestPage(page, page.isEmpty() ? afterId : page.getLast().id(), rows.size() > limit);
    }

    /** 发申请与处理申请始终先按数值 ID 升序锁用户，再锁申请，避免互加好友时反向死锁。 */
    private void lockPair(long a, long b, boolean requireBothActive) {
        var first = users.lockUser(Math.min(a, b));
        var second = users.lockUser(Math.max(a, b));
        if (first == null || second == null || (requireBothActive &&
                (!first.status().equals("ACTIVE") || !second.status().equals("ACTIVE")))) throw missingUser();
    }
    private void active(Identity identity) { if (!auth.active(identity)) throw AuthException.unauthorized(); }
    private void checkLimit(int limit) { if (limit < 1 || limit > 100) throw invalid(); }
    private long number(String value, boolean zero) {
        if (value == null || !value.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException error) { throw invalid(); }
    }
    private static AuthException invalid() { return new AuthException(400, "INVALID_REQUEST", "请检查账号、附言和分页参数"); }
    private static AuthException missingUser() { return new AuthException(404, "USER_NOT_FOUND", "未找到可添加的账号"); }
    private static AuthException missingRequest() { return new AuthException(404, "REQUEST_NOT_FOUND", "申请不存在或无权处理"); }
}
