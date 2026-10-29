package de.sdmxdw.process;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Scheduler for process schedules and the daily retention cleanup. */
@Configuration
@EnableScheduling
public class ProcessConfig {

  @Bean
  public ThreadPoolTaskScheduler processTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("process-schedule-");
    scheduler.initialize();
    return scheduler;
  }
}
