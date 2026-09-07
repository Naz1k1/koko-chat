package dev.koko.chat.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.Identity;
import dev.koko.chat.contact.ContactMapper;
import dev.koko.chat.message.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;
import static dev.koko.chat.group.GroupModels.*;

/** 群管理采用普通 Service/Mapper 分层；命令结果与成员修改原子提交。 */
@Service @Profile("local")
public class GroupService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final GroupMapper mapper;
    private final ChatMapper chats;
    private final ChatService chat;
    private final ContactMapper contacts;
    private final AuthMapper users;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public GroupService(GroupMapper mapper, ChatMapper chats, ChatService chat, ContactMapper contacts,
                        AuthMapper users, ObjectMapper json, PlatformTransactionManager transactions) {
        this.mapper=mapper; this.chats=chats; this.chat=chat; this.contacts=contacts; this.users=users; this.json=json;
        tx=new TransactionTemplate(transactions); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Result create(Identity actor, Create body) {
        if(body==null || body.title()==null || body.title().isBlank() || body.title().length()>128) throw invalid();
        String title=body.title().strip(); var ids=ids(body.memberIds());
        if(ids.contains(actor.userId())) throw invalid();
        return execute(actor,body.clientCommandId(),List.of("CREATE",title,ids),()->{
            ids.forEach(id->friend(actor,id));
            long group=RANDOM.nextLong(1,Long.MAX_VALUE);
            mapper.create(group,title,actor.userId());
            mapper.join(group,actor.userId(),"OWNER",UUID.randomUUID().toString(),1,0);
            ids.forEach(id->mapper.join(group,id,"MEMBER",UUID.randomUUID().toString(),1,0));
            return group;
        });
    }
    public Result invite(Identity actor, String id, Invite body) {
        long group=ChatService.number(id,false); epoch(body.membershipEpoch()); var ids=ids(body.memberIds());
        return execute(actor,body.clientCommandId(),List.of("INVITE",group,body.membershipEpoch(),ids),()->{
            var row=locked(actor,group,body.membershipEpoch(),true);
            var current=mapper.members(group).stream().map(m->Long.parseLong(m.userId())).toList();
            var added=ids.stream().filter(user->!current.contains(user)).toList();
            if(current.size()+added.size()>200) throw new AuthException(409,"GROUP_FULL","群成员最多 200 人");
            if(row.latestSeq()==Long.MAX_VALUE) throw new AuthException(503,"SEQUENCE_EXHAUSTED","会话序号已耗尽");
            added.forEach(user->friend(actor,user));
            added.forEach(user->mapper.join(group,user,"MEMBER",UUID.randomUUID().toString(),row.latestSeq()+1,row.latestSeq()));
            return group;
        });
    }
    public Result remove(Identity actor,String id,String userId,Change body) {
        long group=ChatService.number(id,false), user=ChatService.number(userId,false);
        epoch(body.membershipEpoch()); epoch(body.targetEpoch());
        return execute(actor,body.clientCommandId(),List.of("REMOVE",group,user,body.membershipEpoch(),body.targetEpoch()),()->{
            var row=locked(actor,group,body.membershipEpoch(),true);
            if(user==row.ownerId()) throw new AuthException(409,"OWNER_CANNOT_LEAVE","群主不能移除自己，可选择解散群聊");
            var target=chats.member(group,user);
            if(target==null || !target.membershipEpoch().equals(body.targetEpoch())) throw changed();
            mapper.leave(group,user,"REMOVED");
            return group;
        });
    }
    public Result leave(Identity actor,String id,Change body) {
        long group=ChatService.number(id,false); epoch(body.membershipEpoch());
        return execute(actor,body.clientCommandId(),List.of("LEAVE",group,body.membershipEpoch()),()->{
            var row=locked(actor,group,body.membershipEpoch(),false);
            if(actor.userId()==row.ownerId()) throw new AuthException(409,"OWNER_CANNOT_LEAVE","群主不能直接退出，可选择解散群聊");
            mapper.leave(group,actor.userId(),"LEFT"); return group;
        });
    }
    public Result close(Identity actor,String id,Change body) {
        long group=ChatService.number(id,false); epoch(body.membershipEpoch());
        return execute(actor,body.clientCommandId(),List.of("CLOSE",group,body.membershipEpoch()),()->{
            locked(actor,group,body.membershipEpoch(),true);
            mapper.close(group); mapper.removeAll(group); return group;
        });
    }
    public Detail detail(Identity actor,String id) {
        chat.requireActive(actor); long group=ChatService.number(id,false);
        return tx.execute(status->{
            var row=locked(actor,group,null,false);
            return new Detail(id,row.title(),Long.toString(row.ownerId()),chats.member(group,actor.userId()).membershipEpoch(),mapper.members(group));
        });
    }
    private GroupRow locked(Identity actor,long group,String epoch,boolean owner) {
        var row=mapper.lock(group);
        if(row==null || !row.status().equals("ACTIVE")) throw new AuthException(404,"GROUP_NOT_FOUND","群聊不存在或已解散");
        chat.member(actor,group,epoch);
        if(owner && row.ownerId()!=actor.userId()) throw new AuthException(403,"OWNER_REQUIRED","只有群主可以执行此操作");
        return row;
    }
    /** 去重查询先于当前成员检查：成功退出或解散的命令可以安全重放，只返回原群 ID。 */
    private Result execute(Identity actor,String command,Object signature,LongSupplier mutation) {
        chat.requireActive(actor);
        if(command==null || !command.matches("[A-Za-z0-9_-]{1,64}")) throw invalid();
        byte[] hash;
        try { hash=AuthService.digest(json.writeValueAsString(signature)); }
        catch(Exception bad) { throw new IllegalStateException(bad); }
        for(int attempt=0;attempt<3;attempt++) {
            try {
                return tx.execute(status->{
                    chat.requireActive(actor);
                    var previous=mapper.command(actor.userId(),command);
                    if(previous!=null) {
                        if(!Arrays.equals(previous.requestHash(),hash)) throw new AuthException(409,"COMMAND_CONFLICT","同一操作编号不能使用不同参数");
                        return new Result(Long.toString(previous.conversationId()),command);
                    }
                    long group=mutation.getAsLong();
                    mapper.remember(actor.userId(),command,hash,group);
                    return new Result(Long.toString(group),command);
                });
            } catch(DuplicateKeyException race) { /* 同编号并发执行仅一笔事务提交，另一笔回滚后重读结果。 */ }
        }
        throw new AuthException(503,"BUSY","操作繁忙，请使用原操作编号重试");
    }
    private void friend(Identity actor,long id) {
        var user=users.user(id);
        if(user==null || !user.status().equals("ACTIVE") || contacts.friends(actor.userId(),id)==0)
            throw new AuthException(403,"FRIEND_REQUIRED","只能邀请自己的有效好友");
    }
    private List<Long> ids(List<String> values) {
        if(values==null || values.size()>199) throw invalid();
        return values.stream().map(id->ChatService.number(id,false)).distinct().sorted().toList();
    }
    private void epoch(String value) { if(value==null || !value.matches("[a-f0-9-]{36}")) throw invalid(); }
    private static AuthException invalid() { return new AuthException(400,"INVALID_REQUEST","请检查群名称、成员和操作编号"); }
    private static AuthException changed() { return new AuthException(409,"MEMBERSHIP_CHANGED","成员状态已变化，请刷新后操作"); }
}
