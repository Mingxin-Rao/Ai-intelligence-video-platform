package com.example.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pool's settings are a capacity decision, not incidental defaults: they cap
 * how many multi-minute AI tasks run at once, how many may queue, and what
 * happens when both are full. Pinning them means a later "harmless" tweak shows
 * up as a failing test rather than as production back-pressure disappearing.
 */
class ThreadPoolConfigTest {

    @Test
    @DisplayName("The AI executor is sized and bounded as intended")
    void executorIsSizedAndBounded() {
        ThreadPoolTaskExecutor executor =
                (ThreadPoolTaskExecutor) new ThreadPoolConfig().aiTaskExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(4);
        assertThat(executor.getMaxPoolSize()).isEqualTo(8);
        // Named so a thread dump immediately shows which pool is stuck on FFmpeg
        assertThat(executor.getThreadNamePrefix()).isEqualTo("AI-Thread-");
    }

    @Test
    @DisplayName("A saturated pool runs work on the caller instead of dropping it")
    void saturatedPoolAppliesBackPressure() {
        ThreadPoolTaskExecutor executor =
                (ThreadPoolTaskExecutor) new ThreadPoolConfig().aiTaskExecutor();

        // CallerRunsPolicy is the back-pressure mechanism: once 8 threads are busy
        // and 100 tasks are queued, the submitter executes the work itself, which
        // slows intake rather than silently discarding a user's task.
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    @DisplayName("Submitted work actually runs")
    void submittedWorkRuns() throws Exception {
        ThreadPoolTaskExecutor executor =
                (ThreadPoolTaskExecutor) new ThreadPoolConfig().aiTaskExecutor();

        var future = executor.submit(() -> "done");

        assertThat(future.get()).isEqualTo("done");
        executor.shutdown();
    }
}
