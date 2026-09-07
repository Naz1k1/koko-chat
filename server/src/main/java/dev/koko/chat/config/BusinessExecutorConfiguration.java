package dev.koko.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** 为后续阻塞业务调用预留有界线程池，防止拖慢 Netty 心跳和连接处理。 */
@Configuration(proxyBeanMethods = false)
public class BusinessExecutorConfiguration {
    /** 认证及 Service 任务在此执行；调用方应将拒绝执行转换为可重试的过载错误。 */
    @Bean
    public ThreadPoolTaskExecutor imBusinessExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("koko-im-business-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(256);
        // 不采用 CallerRunsPolicy，避免队列饱和时反而让提交任务的 Netty 线程执行 JDBC。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
