package de.sdmxdw.process;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A process: metadata and steps in the database, the code in {@code folder} below the process root.
 * Not bound to any flow - a run may load data into every dataflow or microdata flow.
 */
@Table("process")
public record Process(
    @Id Long id,
    String name,
    String description,
    String folder,
    String schedule,
    boolean enabled,
    int timeoutMinutes,
    String notifyEmail,
    Long runAfterProcessId,
    String secretNames,
    String paramsJson,
    String createdBy,
    Instant createdAt,
    String updatedBy,
    Instant updatedAt,
    @MappedCollection(idColumn = "process_id", keyColumn = "position") List<ProcessStep> steps) {

  public List<String> secretNameList() {
    return secretNames == null || secretNames.isBlank()
        ? List.of()
        : Arrays.stream(secretNames.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
  }

  /**
   * Runtimes of the script steps ("Python", "R"): a step's runtime follows from the extension of
   * its script, so a process may mix both.
   */
  public List<String> languages() {
    if (steps == null) {
      return List.of();
    }
    boolean python =
        steps.stream()
            .anyMatch(
                s ->
                    s.type() == ProcessStepType.SCRIPT
                        && s.scriptPath() != null
                        && s.scriptPath().toLowerCase().endsWith(".py"));
    boolean r = steps.stream().anyMatch(ProcessStep::isRScript);
    return python && r
        ? List.of("Python", "R")
        : python ? List.of("Python") : r ? List.of("R") : List.of();
  }

  public boolean scheduled() {
    return schedule != null && !schedule.isBlank();
  }
}
