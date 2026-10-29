package de.sdmxdw.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.audit.AuditLogService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

class ProcessServiceTest extends AbstractIntegrationTest {

  @Autowired private ProcessService processService;
  @Autowired private ProcessFiles files;
  @Autowired private AuditLogService auditLogService;
  @Autowired private ProcessFixtures fixtures;
  @Autowired private ProcessRepository processRepository;
  @Autowired private JdbcTemplate jdbc;

  private static ProcessStep script(String key, String path, String needs) {
    return new ProcessStep(null, key, ProcessStepType.SCRIPT, path, "", null, null, needs);
  }

  @Test
  @WithMockUser(username = "dm@x", roles = "DATENMANAGER")
  void createsFolderAndValidatesSteps() {
    String sfx = ProcessFixtures.suffix().toLowerCase();
    Process p = processService.create("Validation " + sfx, "d", null, "dm@x");
    assertThat(p.folder()).isEqualTo("validation-" + sfx.replace("_", "-"));
    assertThat(files.exists(p.folder(), "main.py")).isTrue();
    assertThat(p.steps()).extracting(ProcessStep::stepKey).containsExactly("main");
    assertThatThrownBy(() -> processService.create("Validation " + sfx, null, null, "dm@x"))
        .hasMessageContaining("already exists");
    assertThatThrownBy(() -> processService.create("Other", null, "shared", "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                processService.updateSteps(p.id(), List.of(script("a", "missing.py", "")), "dm@x"))
        .hasMessageContaining("does not exist");
    assertThatThrownBy(
            () ->
                processService.updateSteps(
                    p.id(),
                    List.of(script("a", "main.py", "b"), script("b", "main.py", "")),
                    "dm@x"))
        .hasMessageContaining("not an earlier step");
    assertThatThrownBy(
            () ->
                processService.updateSteps(
                    p.id(),
                    List.of(script("a", "main.py", ""), script("a", "main.py", "")),
                    "dm@x"))
        .hasMessageContaining("Duplicate step key");
    files.write(p.folder(), "notes.txt", "x");
    assertThatThrownBy(
            () -> processService.updateSteps(p.id(), List.of(script("a", "notes.txt", "")), "dm@x"))
        .hasMessageContaining("Python (.py) or R (.R)");
    assertThatThrownBy(
            () ->
                processService.updateSteps(
                    p.id(),
                    List.of(
                        new ProcessStep(
                            null,
                            "agg",
                            ProcessStepType.AGGREGATE,
                            null,
                            "",
                            "TST:NOPE(1.0)",
                            null,
                            "")),
                    "dm@x"))
        .hasMessageContaining("Unknown flow");
    assertThatThrownBy(
            () ->
                processService.updateSteps(
                    p.id(),
                    List.of(
                        new ProcessStep(
                            null, "self", ProcessStepType.PROCESS, null, "", null, p.id(), "")),
                    "dm@x"))
        .hasMessageContaining("cycle");

    var dataflow = fixtures.dataflow(ProcessFixtures.suffix());
    Process saved =
        processService.updateSteps(
            p.id(),
            List.of(
                script("load", "main.py", ""),
                new ProcessStep(
                    null,
                    "publish",
                    ProcessStepType.PROMOTE,
                    null,
                    "",
                    ProcessFixtures.ref(dataflow),
                    null,
                    "load")),
            "dm@x");
    assertThat(saved.steps()).extracting(ProcessStep::stepKey).containsExactly("load", "publish");

    assertThatThrownBy(
            () ->
                processService.updateSettings(
                    p.id(),
                    p.name(),
                    null,
                    "every day",
                    true,
                    30,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    "dm@x"))
        .hasMessageContaining("Invalid schedule");
    assertThatThrownBy(
            () ->
                processService.updateSettings(
                    p.id(),
                    p.name(),
                    null,
                    null,
                    true,
                    30,
                    null,
                    null,
                    List.of("NOPE"),
                    List.of(),
                    "dm@x"))
        .hasMessageContaining("Unknown secret");
    assertThatThrownBy(
            () ->
                processService.updateSettings(
                    p.id(), p.name(), null, null, true, 30, null, p.id(), List.of(), List.of(),
                    "dm@x"))
        .hasMessageContaining("after itself");
    assertThatThrownBy(
            () ->
                processService.updateSettings(
                    p.id(),
                    p.name(),
                    null,
                    null,
                    true,
                    30,
                    null,
                    null,
                    List.of(),
                    List.of(new ProcessParam("Bad Name", null, null, false)),
                    "dm@x"))
        .hasMessageContaining("lower case");
    Process scheduled =
        processService.updateSettings(
            p.id(),
            p.name(),
            "desc",
            "0 0 6 * * MON-FRI",
            false,
            10,
            null,
            null,
            List.of(),
            List.of(new ProcessParam("period", "Period", "latest", true)),
            "dm@x");
    assertThat(scheduled.schedule()).isEqualTo("0 0 6 * * MON-FRI");
    assertThat(processService.params(scheduled))
        .extracting(ProcessParam::name)
        .containsExactly("period");

    assertThat(auditLogService.findByProcess(p.id(), 0, 20).entries())
        .extracting(e -> e.action())
        .contains("CREATE_PROCESS", "UPDATE_STEPS", "UPDATE_PROCESS");
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void economistsCannotSeeOrStartProcesses() {
    assertThatThrownBy(() -> processService.findAll()).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> processService.startRun(1L, Map.of(), false, "oe"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(username = "dm@x", roles = "DATENMANAGER")
  void legacyImportJobsAndScriptsAreMigrated(@TempDir Path legacy) throws Exception {
    String sfx = ProcessFixtures.suffix();
    var df = fixtures.dataflow(sfx);
    Files.writeString(legacy.resolve("bis_import.py"), "print('bis')\n");
    Files.writeString(legacy.resolve("unused_tool.py"), "print('tool')\n");
    Files.writeString(legacy.resolve("sdmxdw_sdk.py"), "# old sdk\n");
    jdbc.update(
        "INSERT INTO import_job (dataflow_id, name, script_path, script_args, cron_schedule, enabled, created_by)"
            + " VALUES (?, ?, ?, '--source BIS', '0 0 6 * * *', true, 'x')",
        df.id(),
        "BIS job " + sfx,
        legacy.resolve("bis_import.py").toString());
    jdbc.update(
        "INSERT INTO import_job (dataflow_id, name, script_path, script_args, cron_schedule, enabled, created_by)"
            + " VALUES (?, ?, '/nonexistent/x.py', '', '0 0 6 * * *', true, 'x')",
        df.id(),
        "Broken " + sfx);

    new LegacyImportMigration(processRepository, files, auditLogService, jdbc, legacy.toString())
        .migrate();

    Process bis = processRepository.findByName("BIS job " + sfx).orElseThrow();
    assertThat(bis.enabled()).isTrue();
    assertThat(bis.schedule()).isEqualTo("0 0 6 * * *");
    assertThat(bis.steps())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.scriptPath()).isEqualTo("bis_import.py");
              assertThat(s.args()).isEqualTo("--source BIS --flow " + ProcessFixtures.ref(df));
            });
    assertThat(files.exists(bis.folder(), "bis_import.py")).isTrue();
    Process broken = processRepository.findByName("Broken " + sfx).orElseThrow();
    assertThat(broken.enabled()).isFalse();
    assertThat(broken.steps()).isEmpty();
    assertThat(broken.description()).contains("was not found");
    assertThat(processRepository.findAll())
        .anyMatch(p -> p.name().startsWith("unused tool") && !p.enabled());
    jdbc.update("DELETE FROM import_job WHERE name IN (?, ?)", "BIS job " + sfx, "Broken " + sfx);
  }
}
