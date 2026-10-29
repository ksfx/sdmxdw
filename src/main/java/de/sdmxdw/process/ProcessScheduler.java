package de.sdmxdw.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

/** Starts processes on their schedule and cleans up old runs (retention). */
@Service
@Order(30)
public class ProcessScheduler implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ProcessScheduler.class);
  static final Duration KEEP_FILES = Duration.ofDays(90);
  static final Duration KEEP_RUNS = Duration.ofDays(365);

  private final ThreadPoolTaskScheduler taskScheduler;
  private final ProcessRepository processRepository;
  private final ProcessRunRepository runRepository;
  private final ProcessRunner runner;
  private final Map<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

  public ProcessScheduler(
      ThreadPoolTaskScheduler taskScheduler,
      ProcessRepository processRepository,
      ProcessRunRepository runRepository,
      @Lazy ProcessRunner runner) {
    this.taskScheduler = taskScheduler;
    this.processRepository = processRepository;
    this.runRepository = runRepository;
    this.runner = runner;
  }

  @Override
  public void run(ApplicationArguments args) {
    processRepository.findAll().forEach(this::reschedule);
  }

  public void reschedule(Process process) {
    cancel(process.id());
    if (!process.enabled() || !process.scheduled()) {
      return;
    }
    scheduled.put(
        process.id(),
        taskScheduler.schedule(() -> fire(process.id()), new CronTrigger(process.schedule())));
    log.info(
        "Scheduled process {} ({}) with '{}'", process.id(), process.name(), process.schedule());
  }

  public void cancel(Long processId) {
    ScheduledFuture<?> existing = scheduled.remove(processId);
    if (existing != null) {
      existing.cancel(false);
    }
  }

  private void fire(Long processId) {
    processRepository
        .findById(processId)
        .filter(Process::enabled)
        .ifPresent(
            p -> {
              try {
                runner.start(p.id(), ProcessRunTrigger.SCHEDULED, Map.of(), false, "scheduler");
              } catch (RuntimeException e) {
                log.error("Could not start scheduled process {}", p.name(), e);
              }
            });
  }

  /** Daily: run data older than a year, handed-over files older than 90 days. */
  @Scheduled(cron = "0 30 3 * * *")
  public void cleanUp() {
    int deleted = runRepository.deleteFinishedBefore(Instant.now().minus(KEEP_RUNS));
    Path runsDir = runner.runsDir();
    int removedDirs = 0;
    if (Files.isDirectory(runsDir)) {
      Instant limit = Instant.now().minus(KEEP_FILES);
      try (Stream<Path> dirs = Files.list(runsDir)) {
        for (Path dir : dirs.toList()) {
          if (Files.getLastModifiedTime(dir).toInstant().isBefore(limit)) {
            deleteRecursively(dir);
            removedDirs++;
          }
        }
      } catch (IOException e) {
        log.warn("Retention cleanup of {} failed", runsDir, e);
      }
    }
    log.info("Process retention: {} runs and {} run folders removed", deleted, removedDirs);
  }

  private static void deleteRecursively(Path dir) throws IOException {
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
