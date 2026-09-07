package dev.koko.chat.message;

import dev.koko.chat.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static dev.koko.chat.message.ChatModels.*;

/** HTTP 负责建会话、目录分页和固定上界补拉；消息创建只开放 Netty SEND 入口。 */
@RestController @RequestMapping("/api/conversations") @Profile("local")
public class ChatController {
    private final AuthService auth;private final ChatService chat;
    public ChatController(AuthService auth,ChatService chat) { this.auth=auth;this.chat=chat; }
    @PostMapping("/direct") public ConversationView direct(@RequestHeader(value="Authorization",required=false) String token,@Valid @RequestBody DirectRequest body) {
        return chat.createDirect(auth.authenticate(token),body.account());
    }
    @GetMapping public ConversationPage list(@RequestHeader(value="Authorization",required=false) String token,
            @RequestParam(defaultValue="0") String afterId,@RequestParam(defaultValue="50") int limit) {
        return chat.conversations(auth.authenticate(token),afterId,limit);
    }
    @GetMapping("/{id}") public ConversationView summary(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) {
        return chat.summary(auth.authenticate(token),id);
    }
    @GetMapping("/{id}/messages") public MessagePage history(@RequestHeader(value="Authorization",required=false) String token,
            @PathVariable String id,@RequestParam(defaultValue="0") String afterSeq,@RequestParam(required=false) String toSeq,
            @RequestParam(defaultValue="50") int limit) {
        return chat.history(auth.authenticate(token),id,afterSeq,toSeq,limit);
    }
}
