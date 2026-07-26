package com.example.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync // Enable async annotation support
public class ThreadPoolConfig {

    @Bean("aiTaskExecutor") // Give the thread pool a name
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);

        // 3. Queue capacity: if all 8 are busy, new tasks queue at the door, up to 100 of them
        executor.setQueueCapacity(100);

        // 4. Thread name prefix: makes it easy to see in the logs who did the work
        executor.setThreadNamePrefix("AI-Thread-");

        // 5. Rejection policy: if the queue is full (100), what to do with new tasks?
        // CallerRunsPolicy: let the boss who submitted the task (main thread) handle it, don't throw the task away.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        return executor;
    }
}