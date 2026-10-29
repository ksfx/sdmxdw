package de.sdmxdw.process;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** One execution of a process, with the parameters and the code version it ran with. */
@Table("process_run")
public record ProcessRun(
    @Id Long id,
    Long processId,
    Long parentRunId,
    ProcessRunTrigger trigger,
    String triggeredBy,
    String paramsJson,
    boolean dryRun,
    String commitSha,
    ProcessRunStatus status,
    String message,
    Instant queuedAt,
    Instant startedAt,
    Instant finishedAt) {

  public static ProcessRun queue(
      Long processId,
      Long parentRunId,
      ProcessRunTrigger trigger,
      String triggeredBy,
      String paramsJson,
      boolean dryRun) {
    return new ProcessRun(
        null,
        processId,
        parentRunId,
        trigger,
        triggeredBy,
        paramsJson,
        dryRun,
        null,
        ProcessRunStatus.QUEUED,
        null,
        Instant.now(),
        null,
        null);
  }

  public ProcessRun started(String commit) {
    return new ProcessRun(
        id,
        processId,
        parentRunId,
        trigger,
        triggeredBy,
        paramsJson,
        dryRun,
        commit,
        ProcessRunStatus.RUNNING,
        null,
        queuedAt,
        Instant.now(),
        null);
  }

  public ProcessRun finished(ProcessRunStatus newStatus, String newMessage) {
    return new ProcessRun(
        id,
        processId,
        parentRunId,
        trigger,
        triggeredBy,
        paramsJson,
        dryRun,
        commitSha,
        newStatus,
        newMessage,
        queuedAt,
        startedAt == null ? Instant.now() : startedAt,
        Instant.now());
  }

  public Duration duration() {
    if (startedAt == null) {
      return null;
    }
    return Duration.between(startedAt, finishedAt == null ? Instant.now() : finishedAt);
  }
}
