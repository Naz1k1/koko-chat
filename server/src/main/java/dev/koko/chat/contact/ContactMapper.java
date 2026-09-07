package dev.koko.chat.contact;

import org.apache.ibatis.annotations.*;
import org.springframework.context.annotation.Profile;
import java.util.List;
import static dev.koko.chat.contact.ContactModels.*;

/** 用户对的事务锁由 Service 统一获取；所有申请查询都约束当前用户身份。 */
@Mapper @Profile("local")
public interface ContactMapper {
    String REQUEST_COLUMNS = "r.id,r.sender_id,r.receiver_id,s.account sender_account,s.nickname sender_nickname,"
            + "u.account receiver_account,u.nickname receiver_nickname,r.greeting,r.status,r.created_at,r.handled_at";
    String REQUEST_JOIN = " FROM friend_request r JOIN app_user s ON s.id=r.sender_id JOIN app_user u ON u.id=r.receiver_id ";

    @Select("SELECT COUNT(*) FROM friendship WHERE user_id=#{user} AND friend_id=#{peer}")
    int friends(long user, long peer);
    @Select("SELECT id,sender_id,receiver_id,greeting,status,created_at,handled_at FROM friend_request WHERE pending_pair=#{pair}")
    RequestRow pending(String pair);
    @Select("SELECT id,sender_id,receiver_id,greeting,status,created_at,handled_at FROM friend_request WHERE id=#{id}")
    RequestRow request(long id);
    @Select("SELECT id,sender_id,receiver_id,greeting,status,created_at,handled_at FROM friend_request WHERE id=#{id} FOR UPDATE")
    RequestRow lockRequest(long id);
    @Select("SELECT " + REQUEST_COLUMNS + REQUEST_JOIN + "WHERE r.id=#{id} AND (r.sender_id=#{user} OR r.receiver_id=#{user})")
    RequestDetails details(long id, long user);
    @Insert("INSERT INTO friend_request(id,sender_id,receiver_id,greeting) VALUES(#{id},#{sender},#{receiver},#{greeting})")
    void insertRequest(long id, long sender, long receiver, String greeting);
    @Insert("INSERT INTO friendship(user_id,friend_id) VALUES(#{user},#{peer})")
    void insertFriend(long user, long peer);
    @Update("UPDATE friend_request SET status=#{status},handled_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND status='PENDING'")
    int handle(long id, String status);

    @Select("SELECT " + REQUEST_COLUMNS + REQUEST_JOIN + """
        WHERE r.id>#{after} AND (r.sender_id=#{user} OR r.receiver_id=#{user})
        AND (#{direction}='all' OR (#{direction}='incoming' AND r.receiver_id=#{user})
             OR (#{direction}='outgoing' AND r.sender_id=#{user}))
        AND (#{status}='ALL' OR r.status=#{status}) ORDER BY r.id LIMIT #{limit}
        """)
    List<RequestDetails> requests(long user, long after, String direction, String status, int limit);
    @Select("""
        SELECT CAST(u.id AS CHAR) id,u.account,u.nickname,f.remark
        FROM friendship f JOIN app_user u ON u.id=f.friend_id
        WHERE f.user_id=#{user} AND f.friend_id>#{after} AND u.status='ACTIVE'
        ORDER BY f.friend_id LIMIT #{limit}
        """)
    List<FriendView> list(long user, long after, int limit);
}
