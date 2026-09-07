package dev.koko.chat.auth;

import org.apache.ibatis.annotations.*;
import org.springframework.context.annotation.Profile;
import java.time.LocalDateTime;
import java.util.List;
import static dev.koko.chat.auth.AuthModels.*;

/** 普通 MyBatis 数据访问层；条件更新与行锁保证刷新、退出和同设备替换不会相互覆盖。 */
@Mapper
@Profile("local")
public interface AuthMapper {
    @Select("SELECT id,account,password_hash,nickname,status FROM app_user WHERE account=#{account}")
    UserRow userByAccount(String account);
    @Select("SELECT id,account,password_hash,nickname,status FROM app_user WHERE id=#{id}")
    UserRow user(long id);
    @Select("SELECT id,account,password_hash,nickname,status FROM app_user WHERE id=#{id} FOR UPDATE")
    UserRow lockUser(long id);
    @Insert("INSERT INTO app_user(id,account,password_hash,nickname) VALUES(#{id},#{account},#{passwordHash},#{nickname})")
    void insertUser(UserRow user);
    @Select("SELECT id FROM auth_session WHERE user_id=#{userId} AND device_id=#{deviceId} AND revoked_at IS NULL")
    List<String> deviceSessions(long userId, String deviceId);
    @Update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=#{userId} AND device_id=#{deviceId} AND revoked_at IS NULL")
    void revokeDevice(long userId, String deviceId);
    @Insert("""
        INSERT INTO auth_session(id,user_id,device_id,refresh_token_hash,expires_at,access_token_hash,access_expires_at)
        VALUES(#{id},#{userId},#{deviceId},#{refreshHash},#{expiresAt},#{accessHash},#{accessExpiresAt})
        """)
    void insertSession(String id, long userId, String deviceId, byte[] refreshHash, LocalDateTime expiresAt,
                       byte[] accessHash, LocalDateTime accessExpiresAt);
    @Select("""
        SELECT s.id,s.user_id,s.device_id,s.expires_at FROM auth_session s JOIN app_user u ON u.id=s.user_id
        WHERE s.access_token_hash=#{hash} AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(3)
        AND s.access_expires_at>UTC_TIMESTAMP(3) AND u.status='ACTIVE'
        """)
    SessionRow byAccess(byte[] hash);
    @Select("SELECT id,user_id,device_id,expires_at FROM auth_session WHERE refresh_token_hash=#{hash} AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(3) FOR UPDATE")
    SessionRow lockRefresh(byte[] hash);
    @Update("UPDATE auth_session SET access_token_hash=#{accessHash},access_expires_at=#{accessExpiresAt},refresh_token_hash=#{refreshHash} WHERE id=#{id}")
    void rotate(String id, byte[] accessHash, LocalDateTime accessExpiresAt, byte[] refreshHash);
    @Select("SELECT id,user_id,device_id,expires_at FROM auth_session WHERE refresh_token_hash=#{hash} FOR UPDATE")
    SessionRow lockLogout(byte[] hash);
    @Update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)) WHERE id=#{id}")
    void revoke(String id);
    @Select("""
        SELECT COUNT(*) FROM auth_session s JOIN app_user u ON u.id=s.user_id
        WHERE s.id=#{sessionId} AND s.user_id=#{userId} AND s.device_id=#{deviceId}
        AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(3) AND u.status='ACTIVE'
        """)
    int active(Identity identity);
}
