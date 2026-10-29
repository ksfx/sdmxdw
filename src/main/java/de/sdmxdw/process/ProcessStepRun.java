package de.sdmxdw.process;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Status, log and load report of one step within a run. */
@Table("process_step_run")
public record ProcessStepRun(
    @Id Long id,
    Long runId,
    int position,
    String stepKey,
    ProcessStepType type,
    ProcessStepRunStatus status,
    Integer exitCode,
    String log,
    String loadReport,
    Instant startedAt,
    Instant finishedAt) {

  public static ProcessStepRun pending(Long runId, int position, ProcessStep step) {
    return new ProcessStepRun(
        null,
        runId,
        position,
        step.stepKey(),
        step.type(),
        ProcessStepRunStatus.PENDING,
        null,
        "",
        null,
        null,
        null);
  }

  public ProcessStepRun withStatus(ProcessStepRunStatus newStatus) {
    Instant start = newStatus == ProcessStepRunStatus.RUNNING ? Instant.now() : startedAt;
    Instant end =
        newStatus == ProcessStepRunStatus.RUNNING || newStatus == ProcessStepRunStatus.PENDING
            ? null
            : Instant.now();
    return new ProcessStepRun(
        id, runId, position, stepKey, type, newStatus, exitCode, log, loadReport, start, end);
  }

  public ProcessStepRun withResult(Integer newExitCode, String newLog, String newLoadReport) {
    return new ProcessStepRun(
        id,
        runId,
        position,
        stepKey,
        type,
        status,
        newExitCode,
        newLog,
        newLoadReport,
        startedAt,
        finishedAt);
  }
}
