package de.sdmxdw.process;

import de.sdmxdw.data.audit.AuditLogService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time migration from import jobs and plugin scripts (before #20) to processes. Runs at startup
 * while no process exists: every import job becomes a process with one script step (the script
 * copied into its folder; the dataflow it was bound to becomes the step argument {@code --flow}),
 * every plugin script without job becomes a paused process. Schedules and the enabled flag are
 * kept; jobs whose script is missing are migrated paused and without steps.
 */
@Component
@Order(10)
public class LegacyImportMigration implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(LegacyImportMigration.class);
  private static final String USER = "migration";

  private final ProcessRepository processRepository;
  private final ProcessFiles files;
  private final AuditLogService auditLogService;
  private final JdbcTemplate jdbc;
  private final Path legacyScriptsDir;

  public LegacyImportMigration(
      ProcessRepository processRepository,
      ProcessFiles files,
      AuditLogService auditLogService,
      JdbcTemplate jdbc,
      @Value("${sdmxdw.plugins.scripts-dir:}") String legacyScriptsDir) {
    this.processRepository = processRepository;
    this.files = files;
    this.auditLogService = auditLogService;
    this.jdbc = jdbc;
    this.legacyScriptsDir =
        legacyScriptsDir == null || legacyScriptsDir.isBlank() ? null : Path.of(legacyScriptsDir);
  }

  @Override
  public void run(ApplicationArguments args) {
    files.ensureSharedSdks();
    if (processRepository.count() == 0) {
      migrate();
    }
  }

  /** Converts all import jobs and unreferenced plugin scripts (separately callable for tests). */
  void migrate() {
    List<Map<String, Object>> jobs;
    try {
      jobs =
          jdbc.queryForList(
              "SELECT j.id, j.name, j.script_path, j.script_args, j.cron_schedule, j.enabled,"
                  + " d.agency_id, d.artefact_id, d.version FROM import_job j"
                  + " JOIN dataflow d ON d.id = j.dataflow_id ORDER BY j.id");
    } catch (DataAccessException e) {
      jobs = List.of(); // tables already dropped
    }
    Set<String> usedScripts = new HashSet<>();
    int migrated = 0;
    for (Map<String, Object> job : jobs) {
      Path script = Path.of((String) job.get("script_path"));
      String fileName = script.getFileName().toString();
      usedScripts.add(fileName);
      Path source = Files.isRegularFile(script) ? script : legacy(fileName);
      String flow =
          job.get("agency_id") + ":" + job.get("artefact_id") + "(" + job.get("version") + ")";
      String stepArgs = (((String) job.get("script_args")) + " --flow " + flow).strip();
      String description =
          "Migrated from import job #"
              + job.get("id")
              + (source == null
                  ? ". The script " + script + " was not found - add a script and a step."
                  : ".");
      create(
          (String) job.get("name"),
          description,
          source,
          stepArgs,
          // paused jobs keep their schedule; it only fires once the process is activated
          source != null ? (String) job.get("cron_schedule") : null,
          source != null && Boolean.TRUE.equals(job.get("enabled")));
      migrated++;
    }
    if (legacyScriptsDir != null && Files.isDirectory(legacyScriptsDir)) {
      try (Stream<Path> scripts = Files.list(legacyScriptsDir)) {
        for (Path script : scripts.sorted().toList()) {
          String name = script.getFileName().toString();
          if (!name.endsWith(".py") || name.equals("sdmxdw_sdk.py") || usedScripts.contains(name)) {
            continue;
          }
          create(
              name.substring(0, name.length() - 3).replace('_', ' '),
              "Migrated plugin script. Set the target flow in the step arguments: --flow AGENCY:ID(VERSION)",
              script,
              "--flow AGENCY:ID(1.0.0)",
              null,
              false);
          migrated++;
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    if (migrated > 0) {
      log.info(
          "Migrated {} import jobs / plugin scripts to processes in {}", migrated, files.root());
    }
  }

  private Path legacy(String fileName) {
    if (legacyScriptsDir == null) {
      return null;
    }
    Path p = legacyScriptsDir.resolve(fileName);
    return Files.isRegularFile(p) ? p : null;
  }

  private void create(
      String name, String description, Path script, String args, String schedule, boolean enabled) {
    String folder = uniqueFolder(ProcessService.slug(name));
    String uniqueName = uniqueName(name);
    files.ensureFolder(folder, null, null);
    List<ProcessStep> steps = new ArrayList<>();
    if (script != null) {
      String fileName = script.getFileName().toString();
      try {
        Files.copy(script, files.folderPath(folder).resolve(fileName));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      steps.add(
          new ProcessStep(null, "main", ProcessStepType.SCRIPT, fileName, args, null, null, ""));
    }
    Instant now = Instant.now();
    Process saved =
        processRepository.save(
            new Process(
                null,
                uniqueName,
                description,
                folder,
                schedule,
                enabled,
                30,
                null,
                null,
                "",
                "[]",
                USER,
                now,
                USER,
                now,
                steps));
    auditLogService.recordProcess(saved.id(), "CREATE_PROCESS", USER, description);
  }

  private String uniqueFolder(String base) {
    String folder = base;
    for (int i = 2;
        processRepository.findByFolder(folder).isPresent()
            || Files.exists(files.folderPath(folder));
        i++) {
      folder = base + "-" + i;
    }
    return folder;
  }

  private String uniqueName(String base) {
    String name = base;
    for (int i = 2; processRepository.findByName(name).isPresent(); i++) {
      name = base + " (" + i + ")";
    }
    return name;
  }
}
