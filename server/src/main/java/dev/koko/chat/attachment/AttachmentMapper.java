package dev.koko.chat.attachment;

import org.apache.ibatis.annotations.*;
import org.springframework.context.annotation.Profile;
import static dev.koko.chat.attachment.AttachmentModels.*;

/** 元数据与消息引用由会话行锁保护，文件字节上传不持有数据库事务。 */
@Mapper @Profile("local")
public interface AttachmentMapper {
    String COLUMNS="id,owner_id,conversation_id,membership_epoch,object_key,name,size,sha256,kind,content_type,status,message_id";
    @Select("SELECT "+COLUMNS+" FROM attachment WHERE id=#{id}") Row find(String id);
    @Insert("INSERT INTO attachment(id,owner_id,conversation_id,membership_epoch,object_key,name,size,sha256,kind) VALUES(#{id},#{ownerId},#{conversationId},#{membershipEpoch},#{objectKey},#{name},#{size},#{sha256},#{kind})")
    void insert(Row row);
    @Update("UPDATE attachment SET status='READY',content_type=#{type} WHERE id=#{id} AND status='PENDING'") void ready(String id,String type);
    @Update("UPDATE attachment SET status='ATTACHED',message_id=#{message} WHERE id=#{id} AND status='READY'") int attach(String id,long message);
    @Select("SELECT id FROM attachment WHERE status IN ('PENDING','READY') AND expires_at<UTC_TIMESTAMP(3) ORDER BY expires_at LIMIT 100") java.util.List<String> expiring();
    @Update("UPDATE attachment SET status='EXPIRED' WHERE id=#{id} AND status IN ('PENDING','READY') AND expires_at<UTC_TIMESTAMP(3)") int expire(String id);
    @Update("UPDATE attachment SET expires_at=GREATEST(expires_at,DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 10 MINUTE)) WHERE id=#{id} AND status IN ('PENDING','READY')") void protectUpload(String id);
    @Select("SELECT id FROM attachment WHERE status='EXPIRED' AND (cleanup_at IS NULL OR cleanup_at<DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 HOUR)) ORDER BY cleanup_at,id LIMIT 100") java.util.List<String> expired();
    @Update("UPDATE attachment SET cleanup_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='EXPIRED'") void cleaned(String id);
}
