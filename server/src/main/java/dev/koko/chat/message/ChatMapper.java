package dev.koko.chat.message;

import org.apache.ibatis.annotations.*;
import org.springframework.context.annotation.Profile;
import java.util.List;
import static dev.koko.chat.message.ChatModels.*;

/** 会话行锁串行分配 seq；消息、序号和 Outbox 由 Service 在一个短事务内提交。 */
@Mapper @Profile("local")
public interface ChatMapper {
    @Select("SELECT id,latest_seq,status,type FROM conversation WHERE direct_key=#{key}") ConversationRow direct(String key);
    @Insert("INSERT INTO conversation(id,type,direct_key,created_by) VALUES(#{id},'DIRECT',#{key},#{creator})")
    void insertConversation(long id,String key,long creator);
    @Insert("INSERT INTO conversation_member(conversation_id,user_id,membership_epoch,join_seq,last_read_seq) VALUES(#{conversation},#{user},#{epoch},1,0)")
    void insertMember(long conversation,long user,String epoch);
    @Select("SELECT id,latest_seq,status,type FROM conversation WHERE id=#{id} FOR UPDATE") ConversationRow lockConversation(long id);
    @Select("SELECT id,latest_seq,status,type FROM conversation WHERE id=#{id}") ConversationRow conversation(long id);
    @Select("SELECT conversation_id,user_id,membership_epoch,join_seq,status,last_read_seq FROM conversation_member WHERE conversation_id=#{conversation} AND user_id=#{user}")
    MemberRow member(long conversation,long user);
    @Select("SELECT conversation_id,user_id,membership_epoch,join_seq,status,last_read_seq FROM conversation_member WHERE conversation_id=#{conversation} AND status='ACTIVE'")
    List<MemberRow> members(long conversation);
    String SUMMARY_COLUMNS = "CAST(c.id AS CHAR) id,CAST(u.id AS CHAR) peer_id,u.account,"
            + "CASE WHEN c.type='GROUP' THEN c.title ELSE u.nickname END nickname,m.membership_epoch,"
            + "CAST(m.join_seq AS CHAR) visible_from_seq,CAST(c.latest_seq AS CHAR) latest_seq,c.type,CAST(c.owner_id AS CHAR) owner_id,"
            + "CAST(m.last_read_seq AS CHAR) last_read_seq,"
            + "CAST((SELECT COUNT(*) FROM message unread WHERE unread.conversation_id=c.id "
            + "AND unread.seq>m.last_read_seq AND unread.seq>=m.join_seq AND unread.sender_id<>m.user_id) AS CHAR) unread_count,CAST(peer.last_read_seq AS CHAR) peer_last_read_seq";
    String SUMMARY_JOIN = " FROM conversation c JOIN conversation_member m ON m.conversation_id=c.id AND m.user_id=#{user} "
            + "LEFT JOIN conversation_member peer ON c.type='DIRECT' AND peer.conversation_id=c.id AND peer.user_id<>#{user} AND peer.status='ACTIVE' "
            + "LEFT JOIN app_user u ON u.id=peer.user_id ";
    @Select("SELECT " + SUMMARY_COLUMNS + SUMMARY_JOIN
            + "WHERE c.status='ACTIVE' AND m.status='ACTIVE' AND c.id>#{after} ORDER BY c.id LIMIT #{limit}")
    List<ConversationView> conversations(long user,long after,int limit);
    @Select("SELECT " + SUMMARY_COLUMNS + SUMMARY_JOIN + "WHERE c.id=#{id} AND c.status='ACTIVE' AND m.status='ACTIVE'")
    ConversationView summary(long id,long user);
    String MESSAGE_COLUMNS="id,conversation_id,seq,sender_id,sender_membership_epoch,client_msg_id,type,JSON_UNQUOTE(JSON_EXTRACT(body,'$.text')) text,body_hash,server_time";
    @Select("SELECT "+MESSAGE_COLUMNS+" FROM message WHERE sender_id=#{user} AND client_msg_id=#{clientId}") MessageRow byClient(long user,String clientId);
    @Select("SELECT "+MESSAGE_COLUMNS+" FROM message WHERE id=#{id}") MessageRow message(long id);
    @Select("SELECT "+MESSAGE_COLUMNS+" FROM message WHERE conversation_id=#{conversation} AND seq>#{after} AND seq<=#{to} ORDER BY seq LIMIT #{limit}")
    List<MessageRow> messages(long conversation,long after,long to,int limit);
    @Update("UPDATE conversation SET latest_seq=latest_seq+1 WHERE id=#{id}") void advance(long id);
    @Insert("""
        INSERT INTO message(id,conversation_id,seq,sender_id,sender_membership_epoch,client_msg_id,type,body,body_hash)
        VALUES(#{id},#{conversation},#{seq},#{sender},#{epoch},#{clientId},'TEXT',#{body},#{hash})
        """) void insertMessage(long id,long conversation,long seq,long sender,String epoch,String clientId,String body,byte[] hash);
    @Insert("INSERT INTO message_outbox(event_id,message_id,payload) VALUES(#{event},#{message},#{payload})")
    void insertOutbox(String event,long message,String payload);
    @Select("SELECT COUNT(*) FROM message_outbox WHERE status<>'PUBLISHED'") int pendingCount();
    @Insert("""
        INSERT INTO device_cursor(user_id,device_id,conversation_id,membership_epoch,received_seq)
        VALUES(#{user},#{device},#{conversation},#{epoch},#{seq})
        ON DUPLICATE KEY UPDATE received_seq=GREATEST(received_seq,#{seq})
        """) void receipt(long user,String device,long conversation,String epoch,long seq);

    @Select("SELECT received_seq FROM device_cursor WHERE user_id=#{user} AND device_id=#{device} AND conversation_id=#{conversation} AND membership_epoch=#{epoch}")
    Long receivedSeq(long user,String device,long conversation,String epoch);
    @Update("UPDATE conversation_member SET last_read_seq=GREATEST(last_read_seq,#{seq}) WHERE conversation_id=#{conversation} AND user_id=#{user} AND membership_epoch=#{epoch} AND status='ACTIVE'")
    void read(long user,long conversation,String epoch,long seq);
}
