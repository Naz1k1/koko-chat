package dev.koko.chat.ops;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 运维采集和归档使用独立调度线程，故障探测不会阻塞业务 Outbox 或通话超时扫描。 */
@Configuration(proxyBeanMethods=false) @Profile("local")
public class OperationsScheduling {
    @Bean public ThreadPoolTaskScheduler taskScheduler() { return scheduler("koko-business-scheduled-",1); }
    @Bean public ThreadPoolTaskScheduler operationsScheduler() { return scheduler("koko-ops-scheduled-",2); }
    private static ThreadPoolTaskScheduler scheduler(String prefix,int size) {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(size);scheduler.setThreadNamePrefix(prefix);scheduler.setAwaitTerminationSeconds(3);return scheduler;
    }
}
