package dev.koko.chat.group;

import org.apache.ibatis.annotations.*;
import org.springframework.context.annotation.Profile;
import java.util.List;
import static dev.koko.chat.group.GroupModels.*;

/** 群行锁与 SEND 共用同一 conversation 行，保证 joinSeq 对应明确的加入时刻。 */
@Mapper @Profile("local")
public interface GroupMapper {
    @Select("SELECT id,title,owner_id,latest_seq,status FROM conversation WHERE id=#{id} AND type='GROUP' FOR UPDATE")
    GroupRow lock(long id);
    @Insert("INSERT INTO conversation(id,type,title,owner_id,created_by) VALUES(#{id},'GROUP',#{title},#{owner},#{owner})")
    void create(long id, String title, long owner);
    @Insert("""
        INSERT INTO conversation_member(conversation_id,user_id,role,membership_epoch,join_seq,last_read_seq)
        VALUES(#{group},#{user},#{role},#{epoch},#{join},#{previous})
        ON DUPLICATE KEY UPDATE role=#{role},status='ACTIVE',membership_epoch=#{epoch},join_seq=#{join},
        last_read_seq=#{previous},joined_at=UTC_TIMESTAMP(3),left_at=NULL
        """)
    void join(long group, long user, String role, String epoch, long join, long previous);
    @Select("""
        SELECT CAST(m.user_id AS CHAR) user_id,u.account,u.nickname,m.role,m.membership_epoch
        FROM conversation_member m JOIN app_user u ON u.id=m.user_id
        WHERE m.conversation_id=#{group} AND m.status='ACTIVE' ORDER BY m.user_id
        """)
    List<Member> members(long group);
    @Update("UPDATE conversation_member SET status=#{status},left_at=UTC_TIMESTAMP(3) WHERE conversation_id=#{group} AND user_id=#{user} AND status='ACTIVE'")
    void leave(long group, long user, String status);
    @Update("UPDATE conversation SET status='CLOSED' WHERE id=#{group}")
    void close(long group);
    @Update("UPDATE conversation_member SET status='REMOVED',left_at=UTC_TIMESTAMP(3) WHERE conversation_id=#{group} AND status='ACTIVE'")
    void removeAll(long group);
    @Select("SELECT request_hash,conversation_id FROM group_command WHERE user_id=#{user} AND client_command_id=#{command}")
    CommandRow command(long user, String command);
    @Insert("INSERT INTO group_command(user_id,client_command_id,request_hash,conversation_id) VALUES(#{user},#{command},#{hash},#{group})")
    void remember(long user, String command, byte[] hash, long group);
}
