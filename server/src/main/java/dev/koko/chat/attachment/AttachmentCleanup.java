package dev.koko.chat.attachment;

import dev.koko.chat.message.ChatMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 与发送使用同一会话锁认领过期记录；保留墓碑并周期复删，覆盖上传进程迟到写入后崩溃的情况。 */
@Component @Profile("local")
public class AttachmentCleanup {
    private final AttachmentMapper mapper;private final ChatMapper chats;private final RustFsStorage storage;private final TransactionTemplate tx;
    public AttachmentCleanup(AttachmentMapper mapper,ChatMapper chats,RustFsStorage storage,PlatformTransactionManager manager) {
        this.mapper=mapper;this.chats=chats;this.storage=storage;tx=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelayString="${koko.attachments.cleanup-delay-ms:60000}",initialDelayString="${koko.attachments.cleanup-delay-ms:60000}")
    public void cleanup() {
        for(String id:mapper.expiring()) tx.executeWithoutResult(status -> {
            var row=mapper.find(id);if(row==null) return;
            chats.lockConversation(row.conversationId());mapper.expire(id);
        });
        for(String id:mapper.expired()) {
            try {
                storage.delete("attachments/"+id);storage.delete("thumbnails/"+id);mapper.cleaned(id);
            } catch(RuntimeException failure) {
                // 删除失败不更新完成时间，下轮继续；日志不输出凭证或 SDK 请求内容。
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("未发送附件清理失败，将在下轮重试：{}",id);
            }
        }
    }
}
