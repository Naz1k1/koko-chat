package dev.koko.chat.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.koko.chat.auth.*;
import dev.koko.chat.auth.AuthModels.Identity;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import static dev.koko.chat.message.ChatModels.*;

/** 普通业务服务：先检查身份和成员周期，随后执行原子保存；MQ 发布完全在事务外。 */
@Service @Profile("local")
public class ChatService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final ChatMapper mapper;
    private final AuthMapper users;
    private final AuthService auth;
    private final ObjectMapper json;
    private final dev.koko.chat.attachment.AttachmentService attachments;
    private final TransactionTemplate tx;
    private final org.springframework.context.ApplicationEventPublisher events;
    public ChatService(ChatMapper mapper,AuthMapper users,AuthService auth,ObjectMapper json,PlatformTransactionManager transactions,org.springframework.context.ApplicationEventPublisher events,dev.koko.chat.attachment.AttachmentService attachments) {
        this.mapper=mapper;this.users=users;this.auth=auth;this.json=json;this.events=events;this.attachments=attachments;
        tx=new TransactionTemplate(transactions);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public ConversationView createDirect(Identity identity,String account) {
        requireActive(identity);
        var peer=users.userByAccount(account.toLowerCase(Locale.ROOT));
        if(peer==null || !peer.status().equals("ACTIVE")) throw new AuthException(404,"USER_NOT_FOUND","未找到可聊天的账号");
        if(peer.id()==identity.userId()) throw new AuthException(400,"SELF_CHAT","请选择其他账号");
        String key=Math.min(peer.id(),identity.userId())+":"+Math.max(peer.id(),identity.userId());
        for(int attempt=0;attempt<3;attempt++) {
            try {
                return tx.execute(status -> {
                    var existing=mapper.direct(key);
                    if(existing!=null) { member(identity,existing.id(),null); return mapper.summary(existing.id(),identity.userId()); }
                    long id=RANDOM.nextLong(1,Long.MAX_VALUE);
                    mapper.insertConversation(id,key,identity.userId());
                    mapper.insertMember(id,identity.userId(),UUID.randomUUID().toString());
                    mapper.insertMember(id,peer.id(),UUID.randomUUID().toString());
                    return mapper.summary(id,identity.userId());
                });
            } catch(DuplicateKeyException race) { /* 对端同时建会话时重读唯一 direct_key，失败事务不会留下单边成员。 */ }
        }
        throw new AuthException(503,"BUSY","创建会话繁忙，请重试");
    }
    public ConversationPage conversations(Identity identity,String after,int limit) {
        requireActive(identity); checkLimit(limit);
        var rows=mapper.conversations(identity.userId(),number(after,true),limit+1);
        boolean more=rows.size()>limit; var page=rows.stream().limit(limit).toList();
        return new ConversationPage(page,page.isEmpty()?after:page.getLast().id(),more);
    }
    public MessagePage history(Identity identity,String conversation,String after,String upper,int limit) {
        requireActive(identity);checkLimit(limit);
        long id=number(conversation,false);
        // 与退出、移除和重新加入共用会话锁，整页历史使用同一个成员周期和可见范围。
        return tx.execute(status -> {
            var chat=mapper.lockConversation(id);var member=member(identity,id,null);
            long to=upper==null?chat.latestSeq():Math.min(number(upper,true),chat.latestSeq());
            long cursor=Math.max(number(after,true),member.joinSeq()-1);
            var rows=mapper.messages(id,cursor,to,limit+1);boolean more=rows.size()>limit;
            var page=rows.stream().limit(limit).map(ChatModels::view).toList();
            return new MessagePage(page,member.membershipEpoch(),Long.toString(member.joinSeq()),Long.toString(to),
                    page.isEmpty()?Long.toString(cursor):page.getLast().seq(),more);
        });
    }
    public ConversationView summary(Identity identity,String id) {
        requireActive(identity);long conversation=number(id,false);
        return tx.execute(status -> {
            mapper.lockConversation(conversation);member(identity,conversation,null);
            return mapper.summary(conversation,identity.userId());
        });
    }

    public MessageView send(Identity identity,SendCommand command) {
        requireActive(identity);
        long conversation=number(command.conversationId(),false);
        if(command.membershipEpoch()==null || !command.membershipEpoch().matches("[a-f0-9-]{36}")) invalid();
        if(command.clientMsgId()==null || !command.clientMsgId().matches("[A-Za-z0-9_-]{1,64}")) invalid();
        String text=command.text();
        boolean file=command.attachmentId()!=null;
        if(file) {
            dev.koko.chat.attachment.AttachmentService.validId(command.attachmentId());
            if(text!=null && !text.isEmpty()) invalid();
        } else if(text==null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length>4096) invalid();
        for(int attempt=0;attempt<3;attempt++) {
            try {
                return tx.execute(status -> {
                    var chat=mapper.lockConversation(conversation);
                    var member=member(identity,conversation,command.membershipEpoch());
                    var attachment=file?attachments.forSend(identity,conversation,member.membershipEpoch(),command.attachmentId()):null;
                    String type=file?attachment.kind():"TEXT";
                    // 多字段正文按键排序，避免 Map.of 在不同 JVM 中的遍历顺序影响重启后的幂等摘要。
                    String body=file?encode(new TreeMap<>(Map.of("text",("IMAGE".equals(type)?"[图片] ":"[文件] ")+attachment.name(),
                            "attachment",dev.koko.chat.attachment.AttachmentModels.reference(attachment)))):encode(Map.of("text",text));
                    if(body.getBytes(StandardCharsets.UTF_8).length>8192) invalid();
                    byte[] hash=AuthService.digest(body);
                    var previous=mapper.byClient(identity.userId(),command.clientMsgId());
                    if(previous!=null) return duplicate(previous,conversation,member.membershipEpoch(),hash);
                    if(mapper.pendingCount()>=10000) throw new AuthException(503,"OUTBOX_FULL","待分发消息过多，请稍后重试");
                    if(chat.latestSeq()==Long.MAX_VALUE) throw new AuthException(503,"SEQUENCE_EXHAUSTED","会话序号已耗尽");
                    long id=RANDOM.nextLong(1,Long.MAX_VALUE), seq=chat.latestSeq()+1;
                    String event=UUID.randomUUID().toString();
                    mapper.advance(conversation);
                    mapper.insertMessage(id,conversation,seq,identity.userId(),member.membershipEpoch(),command.clientMsgId(),body,hash,type,command.attachmentId());
                    if(file) attachments.attach(command.attachmentId(),id);
                    mapper.insertOutbox(event,id,encode(new MessageEvent(1,"message.created",event,Long.toString(id),Long.toString(conversation),Long.toString(seq),0)));
                    return view(mapper.message(id));
                });
            } catch(DuplicateKeyException race) { /* 跨会话复用 clientMsgId 或极低概率 ID 冲突，回滚后重查。 */ }
        }
        throw new AuthException(503,"BUSY","发送繁忙，请使用原消息编号重试");
    }
    private MessageView duplicate(MessageRow old,long conversation,String epoch,byte[] hash) {
        if(old.conversationId()!=conversation || !old.senderMembershipEpoch().equals(epoch) || !Arrays.equals(old.bodyHash(),hash))
            throw new AuthException(409,"IDEMPOTENCY_CONFLICT","同一消息编号不能对应不同内容或会话");
        return view(old);
    }
    public void received(Identity identity,ReceiptCommand command) {
        requireActive(identity);long id=number(command.conversationId(),false),seq=number(command.receivedSeq(),true);
        if(command.membershipEpoch()==null || !command.membershipEpoch().matches("[a-f0-9-]{36}")) invalid();
        tx.executeWithoutResult(status -> {
            var chat=mapper.lockConversation(id);var member=member(identity,id,command.membershipEpoch());
            if(seq<member.joinSeq()-1 || seq>chat.latestSeq()) invalid();
            mapper.receipt(identity.userId(),identity.deviceId(),id,member.membershipEpoch(),seq);
        });
    }
    /** 读进度只在当前设备已确认的连续范围内推进，与退群、重入共用会话行锁。 */
    public ConversationView read(Identity identity,ReadCommand command) {
        requireActive(identity);long id=number(command.conversationId(),false),seq=number(command.readSeq(),true);
        if(command.membershipEpoch()==null || !command.membershipEpoch().matches("[a-f0-9-]{36}")) invalid();
        return tx.execute(status -> {
            var conversation=mapper.lockConversation(id);var member=member(identity,id,command.membershipEpoch());
            Long received=mapper.receivedSeq(identity.userId(),identity.deviceId(),id,member.membershipEpoch());
            long upper=received==null?member.joinSeq()-1:received;
            if(seq<member.joinSeq()-1 || seq>conversation.latestSeq() || seq>upper)
                throw new AuthException(400,"INVALID_READ","已读进度不能超过当前设备已接收的连续位置");
            if(seq>member.lastReadSeq()) {
                mapper.read(identity.userId(),id,member.membershipEpoch(),seq);
                // AFTER_COMMIT 监听器发送提示，失败不回滚数据库事实，快照同步负责补齐。
                events.publishEvent(new ReadChanged(id,identity.userId(),member.membershipEpoch()));
            }
            return mapper.summary(id,identity.userId());
        });
    }
    public MemberRow member(Identity identity,long id,String epoch) {
        var chat=mapper.conversation(id);var member=mapper.member(id,identity.userId());
        if(chat==null || !chat.status().equals("ACTIVE") || !Set.of("DIRECT","GROUP").contains(chat.type()) || member==null || !member.status().equals("ACTIVE"))
            throw new AuthException(403,"NOT_A_MEMBER","无权访问此会话");
        if(epoch!=null && !epoch.equals(member.membershipEpoch())) throw new AuthException(409,"MEMBERSHIP_CHANGED","会话成员状态已变更，请重新同步");
        return member;
    }
    public void requireActive(Identity identity) { if(!auth.active(identity)) throw AuthException.unauthorized(); }
    public static long number(String value,boolean zero) {
        if(value==null || !value.matches(zero?"0|[1-9][0-9]{0,18}":"[1-9][0-9]{0,18}")) invalid();
        try { return Long.parseLong(value); } catch(NumberFormatException bad) { invalid();return 0; }
    }
    private void checkLimit(int limit) { if(limit<1 || limit>100) invalid(); }
    private static void invalid() { throw new AuthException(400,"INVALID_MESSAGE","消息参数不合法：文本最多 4096 UTF-8 字节"); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception bad) { throw new IllegalStateException(bad); } }
}
