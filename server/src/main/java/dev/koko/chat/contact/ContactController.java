package dev.koko.chat.contact;

import dev.koko.chat.auth.*;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static dev.koko.chat.contact.ContactModels.*;

/** 好友管理走已认证 HTTP；接收方 ID 一律取令牌身份，不接受客户端指定处理人。 */
@RestController @RequestMapping("/api") @Profile("local")
public class ContactController {
    private final AuthService auth;
    private final ContactService contacts;
    private final AuthRateLimiter limiter;
    public ContactController(AuthService auth, ContactService contacts, AuthRateLimiter limiter) {
        this.auth = auth; this.contacts = contacts; this.limiter = limiter;
    }
    @PostMapping("/friend-requests")
    public RequestView create(@RequestHeader(value="Authorization", required=false) String token, @Valid @RequestBody CreateRequest body) {
        var identity = auth.authenticate(token);
        // 沿用 Redis 原子固定窗口计数，按已认证用户限制申请写入请求。
        limiter.check("friend-request", Long.toString(identity.userId()), 20);
        return contacts.create(identity, body);
    }
    @PostMapping("/friend-requests/{id}/accept")
    public RequestView accept(@RequestHeader(value="Authorization", required=false) String token, @PathVariable String id) {
        return contacts.handle(auth.authenticate(token), id, true);
    }
    @PostMapping("/friend-requests/{id}/reject")
    public RequestView reject(@RequestHeader(value="Authorization", required=false) String token, @PathVariable String id) {
        return contacts.handle(auth.authenticate(token), id, false);
    }
    @GetMapping("/friend-requests")
    public RequestPage requests(@RequestHeader(value="Authorization", required=false) String token,
            @RequestParam(defaultValue="0") String afterId, @RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="all") String direction, @RequestParam(defaultValue="ALL") String status) {
        return contacts.requests(auth.authenticate(token), afterId, limit, direction, status);
    }
    @GetMapping("/friends")
    public FriendPage friends(@RequestHeader(value="Authorization", required=false) String token,
            @RequestParam(defaultValue="0") String afterId, @RequestParam(defaultValue="50") int limit) {
        return contacts.friends(auth.authenticate(token), afterId, limit);
    }
}
