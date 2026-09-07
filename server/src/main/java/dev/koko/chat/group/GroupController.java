package dev.koko.chat.group;

import dev.koko.chat.auth.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static dev.koko.chat.group.GroupModels.*;

/** 群管理使用 HTTP，群消息仍使用统一 Netty SEND 协议；所有修改带稳定命令编号。 */
@RestController @RequestMapping("/api/groups") @Profile("local")
public class GroupController {
    private final AuthService auth;
    private final GroupService groups;
    public GroupController(AuthService auth,GroupService groups) { this.auth=auth; this.groups=groups; }
    @PostMapping public Result create(@RequestHeader(value="Authorization",required=false) String token,@RequestBody Create body) {
        return groups.create(auth.authenticate(token),body);
    }
    @GetMapping("/{id}") public Detail detail(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id) {
        return groups.detail(auth.authenticate(token),id);
    }
    @PostMapping("/{id}/members") public Result invite(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,@RequestBody Invite body) {
        return groups.invite(auth.authenticate(token),id,body);
    }
    @PostMapping("/{id}/members/{user}/remove") public Result remove(@RequestHeader(value="Authorization",required=false) String token,
            @PathVariable String id,@PathVariable String user,@RequestBody Change body) {
        return groups.remove(auth.authenticate(token),id,user,body);
    }
    @PostMapping("/{id}/leave") public Result leave(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,@RequestBody Change body) {
        return groups.leave(auth.authenticate(token),id,body);
    }
    @PostMapping("/{id}/close") public Result close(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String id,@RequestBody Change body) {
        return groups.close(auth.authenticate(token),id,body);
    }
}
