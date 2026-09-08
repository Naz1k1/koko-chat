package dev.koko.chat.ops;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 认证默认关闭与依赖未知状态，不能被健康快照误报为正常。 */
class OperationsPolicyTest {
    @Test void credentialsFailClosed() {
        assertThat(new OpsAccess("").accepts("Bearer anything")).isFalse();
        assertThatThrownBy(()->new OpsAccess("short")).isInstanceOf(IllegalArgumentException.class);
        var access=new OpsAccess("local-test-only-token-32-characters");
        assertThat(access.accepts(null)).isFalse();assertThat(access.accepts("Bearer local-test-only-token-32-characters")).isTrue();
        assertThat(access.accepts("Bearer local-test-only-token-32-characterX")).isFalse();
    }
    @Test void alertsRecoverWhenDependenciesAndBacklogRecover() {
        assertThat(OperationsMonitor.evaluate(Map.of())).contains("MYSQL_UNAVAILABLE","REDIS_UNAVAILABLE","RABBITMQ_UNAVAILABLE");
        var data=new HashMap<>(Map.of("mysql_up",1d,"redis_up",1d,"rabbitmq_up",1d,"rustfs_up",1d,"business_queued",200d,"replay_oldest_seconds",61d));
        assertThat(OperationsMonitor.evaluate(data)).containsExactlyInAnyOrder("BUSINESS_POOL_PRESSURE","REPLAY_BACKLOG");
        data.put("business_queued",0d);data.put("replay_oldest_seconds",0d);assertThat(OperationsMonitor.evaluate(data)).isEmpty();
    }
}
