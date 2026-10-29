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

  public boolean scheduled() {
    return schedule != null && !schedule.isBlank();
  }
}
