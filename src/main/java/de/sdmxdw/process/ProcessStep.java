package de.sdmxdw.process;

import java.util.Arrays;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One step of a process.
 *
 * @param scriptPath SCRIPT: path relative to the process folder
 * @param args SCRIPT: command line arguments; {@code ${param}} is replaced by the run parameter
 * @param target AGGREGATE / PROMOTE: flow reference such as {@code SNB:DF_KRED(3.0.0)}
 * @param subProcessId PROCESS: the process whose steps run as part of this run
 * @param needs comma-separated keys of steps that must have succeeded before; empty = previous step
 */
@Table("process_step")
public record ProcessStep(
    @Id Long id,
    String stepKey,
    ProcessStepType type,
    String scriptPath,
    String args,
    String target,
    Long subProcessId,
    String needs) {

  public List<String> needsList() {
    return needs == null || needs.isBlank()
        ? List.of()
        : Arrays.stream(needs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  public boolean isRScript() {
    return type == ProcessStepType.SCRIPT
        && scriptPath != null
        && scriptPath.toLowerCase().endsWith(".r");
  }
}
