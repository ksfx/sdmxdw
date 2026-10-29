package de.sdmxdw.process;

import de.sdmxdw.data.audit.AuditLogService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Process metadata (database) and its consistency with the process folder: step keys, script files,
 * built-in targets, sub-process and chaining cycles, schedules, parameters, secrets. Changes are
 * written to the audit log because they are not versioned in Git.
 */
@Service
public class ProcessService {

  private static final Pattern STEP_KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,49}");
  private static final Pattern PARAM_NAME = Pattern.compile("[a-z][a-z0-9_]{0,49}");
  static final String STARTER_PY =
      """
      \"\"\"Starter script of a SDMxDW process.

      Write deliveries with the SDK (they are loaded into Alpha after this step):
          from sdmxdw_sdk import IngestClient, param
          IngestClient("AGENCY:DATAFLOW(1.0.0)").send([
              {"dimensions": {"FREQ": "A", "TIME_PERIOD": "2026"}, "measures": {"OBS_VALUE": "1"}},
          ])
      Read data with sdmxdw_sdk.read_data(...) / read_microdata(...).
      \"\"\"

      from sdmxdw_sdk import param

      print("Hello from", param("name", "SDMxDW"))
      """;

  private final ProcessRepository processRepository;
  private final ProcessRunRepository runRepository;
  private final ProcessStepRunRepository stepRunRepository;
  private final ProcessFiles files;
  private final ProcessRunner runner;
  private final ProcessScheduler scheduler;
  private final ProcessSecretService secretService;
  private final AuditLogService auditLogService;
  private final ObjectMapper objectMapper;

  public ProcessService(
      ProcessRepository processRepository,
      ProcessRunRepository runRepository,
      ProcessStepRunRepository stepRunRepository,
      ProcessFiles files,
      ProcessRunner runner,
      ProcessScheduler scheduler,
      ProcessSecretService secretService,
      AuditLogService auditLogService,
      ObjectMapper objectMapper) {
    this.processRepository = processRepository;
    this.runRepository = runRepository;
    this.stepRunRepository = stepRunRepository;
    this.files = files;
    this.runner = runner;
    this.scheduler = scheduler;
    this.secretService = secretService;
    this.auditLogService = auditLogService;
    this.objectMapper = objectMapper;
  }

  // --- queries
  // -------------------------------------------------------------------------------------

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<Process> findAll() {
    return processRepository.findAllByOrderByNameAsc();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public Process findById(Long id) {
    return processRepository.findById(id).orElseThrow();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public Optional<ProcessRun> latestRun(Long processId) {
    return runRepository.findLatest(processId);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<ProcessRun> runs(Long processId, int page, int size) {
    return runRepository.findByProcessIdAndParentRunIdIsNullOrderByQueuedAtDesc(
        processId, PageRequest.of(Math.max(page, 0), size));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public long countRuns(Long processId) {
    return runRepository.countByProcessIdAndParentRunIdIsNull(processId);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public ProcessRun findRun(Long runId) {
    return runRepository.findById(runId).orElseThrow();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<ProcessStepRun> stepRuns(Long runId) {
    return stepRunRepository.findByRunIdOrderByPosition(runId);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<ProcessRun> subRuns(Long runId) {
    return runRepository.findByParentRunIdOrderByQueuedAt(runId);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<ProcessParam> params(Process process) {
    return runner.params(process);
  }

  public boolean rAvailable() {
    return runner.rAvailable();
  }

  // --- changes
  // -------------------------------------------------------------------------------------

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public Process create(String name, String description, String folder, String user) {
    String cleanName = required(name, "Name");
    String cleanFolder = folder == null || folder.isBlank() ? slug(cleanName) : folder.strip();
    if (!ProcessFiles.FOLDER.matcher(cleanFolder).matches()
        || cleanFolder.equals(ProcessFiles.SHARED)) {
      throw new IllegalArgumentException(
          "Folder must be lower case letters, digits, '-' or '_' (and not 'shared')");
    }
    if (processRepository.findByName(cleanName).isPresent()) {
      throw new IllegalArgumentException("A process named '" + cleanName + "' already exists");
    }
    if (processRepository.findByFolder(cleanFolder).isPresent()) {
      throw new IllegalArgumentException("The folder '" + cleanFolder + "' is already used");
    }
    files.ensureSharedSdks();
    files.ensureFolder(cleanFolder, "main.py", STARTER_PY);
    String script = files.exists(cleanFolder, "main.py") ? "main.py" : null;
    Instant now = Instant.now();
    Process saved =
        processRepository.save(
            new Process(
                null,
                cleanName,
                blankToNull(description),
                cleanFolder,
                null,
                true,
                30,
                null,
                null,
                "",
                "[]",
                user,
                now,
                user,
                now,
                script == null
                    ? List.of()
                    : List.of(
                        new ProcessStep(
                            null, "main", ProcessStepType.SCRIPT, script, "", null, null, ""))));
    auditLogService.recordProcess(saved.id(), "CREATE_PROCESS", user, "folder " + cleanFolder);
    return saved;
  }

  /** Settings: everything except steps. */
  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public Process updateSettings(
      Long id,
      String name,
      String description,
      String schedule,
      boolean enabled,
      int timeoutMinutes,
      String notifyEmail,
      Long runAfterProcessId,
      List<String> secretNames,
      List<ProcessParam> params,
      String user) {
    Process p = findById(id);
    String cleanName = required(name, "Name");
    processRepository
        .findByName(cleanName)
        .filter(other -> !other.id().equals(id))
        .ifPresent(
            other -> {
              throw new IllegalArgumentException(
                  "A process named '" + cleanName + "' already exists");
            });
    String cron = blankToNull(schedule);
    if (cron != null) {
      try {
        CronExpression.parse(cron);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Invalid schedule '" + cron + "': " + e.getMessage());
      }
    }
    if (timeoutMinutes < 1 || timeoutMinutes > 1440) {
      throw new IllegalArgumentException("Timeout must be between 1 and 1440 minutes");
    }
    if (runAfterProcessId != null) {
      if (runAfterProcessId.equals(id)) {
        throw new IllegalArgumentException("A process cannot run after itself");
      }
      // A after B after ... after A would loop forever
      Long current = runAfterProcessId;
      Set<Long> visited = new HashSet<>();
      while (current != null && visited.add(current)) {
        if (current.equals(id)) {
          throw new IllegalArgumentException("Chaining would create a cycle");
        }
        current = processRepository.findById(current).map(Process::runAfterProcessId).orElse(null);
      }
    }
    Set<String> known = new HashSet<>();
    secretService.findAll().forEach(s -> known.add(s.name()));
    List<String> secrets =
        secretNames == null
            ? List.of()
            : secretNames.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    for (String s : secrets) {
      if (!known.contains(s)) {
        throw new IllegalArgumentException("Unknown secret " + s);
      }
    }
    List<ProcessParam> cleanParams = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (ProcessParam param : params == null ? List.<ProcessParam>of() : params) {
      if (param.name() == null || param.name().isBlank()) {
        continue;
      }
      String n = param.name().strip();
      if (!PARAM_NAME.matcher(n).matches()) {
        throw new IllegalArgumentException("Parameter names are lower case, e.g. 'period': " + n);
      }
      if (!names.add(n)) {
        throw new IllegalArgumentException("Duplicate parameter " + n);
      }
      cleanParams.add(
          new ProcessParam(
              n, blankToNull(param.label()), blankToNull(param.defaultValue()), param.required()));
    }
    Process updated =
        new Process(
            p.id(),
            cleanName,
            blankToNull(description),
            p.folder(),
            cron,
            enabled,
            timeoutMinutes,
            blankToNull(notifyEmail),
            runAfterProcessId,
            String.join(",", secrets),
            json(cleanParams),
            p.createdBy(),
            p.createdAt(),
            user,
            Instant.now(),
            p.steps());
    Process saved = processRepository.save(updated);
    auditLogService.recordProcess(id, "UPDATE_PROCESS", user, describeSettingsChange(p, saved));
    scheduler.reschedule(saved);
    return saved;
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public Process setEnabled(Long id, boolean enabled, String user) {
    Process p = findById(id);
    Process saved =
        processRepository.save(
            new Process(
                p.id(),
                p.name(),
                p.description(),
                p.folder(),
                p.schedule(),
                enabled,
                p.timeoutMinutes(),
                p.notifyEmail(),
                p.runAfterProcessId(),
                p.secretNames(),
                p.paramsJson(),
                p.createdBy(),
                p.createdAt(),
                user,
                Instant.now(),
                p.steps()));
    auditLogService.recordProcess(id, enabled ? "ENABLE_PROCESS" : "PAUSE_PROCESS", user, null);
    scheduler.reschedule(saved);
    return saved;
  }

  /** Replaces all steps; validated against the process folder and the other processes. */
  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public Process updateSteps(Long id, List<ProcessStep> steps, String user) {
    Process p = findById(id);
    List<ProcessStep> clean = validateSteps(p, steps);
    Process saved =
        processRepository.save(
            new Process(
                p.id(),
                p.name(),
                p.description(),
                p.folder(),
                p.schedule(),
                p.enabled(),
                p.timeoutMinutes(),
                p.notifyEmail(),
                p.runAfterProcessId(),
                p.secretNames(),
                p.paramsJson(),
                p.createdBy(),
                p.createdAt(),
                user,
                Instant.now(),
                clean));
    auditLogService.recordProcess(
        id,
        "UPDATE_STEPS",
        user,
        clean.stream()
            .map(s -> s.stepKey() + "=" + describe(s))
            .reduce((a, b) -> a + ", " + b)
            .orElse("(none)"));
    return saved;
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public void delete(Long id, String user) {
    Process p = findById(id);
    for (Process other : processRepository.findAll()) {
      if (other.steps() != null
          && other.steps().stream().anyMatch(s -> id.equals(s.subProcessId()))) {
        throw new IllegalArgumentException("Used as sub-process by '" + other.name() + "'");
      }
    }
    if (runner.isActive(id)) {
      throw new IllegalArgumentException("The process is running");
    }
    scheduler.cancel(id);
    processRepository.delete(p);
    // the folder stays: it is code (and possibly versioned in Git)
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public ProcessRun startRun(Long id, Map<String, String> params, boolean dryRun, String user) {
    return runner.start(id, ProcessRunTrigger.MANUAL, params, dryRun, user);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public boolean cancelRun(Long runId) {
    return runner.cancel(runId);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public boolean isRunning(Long processId) {
    return runner.isActive(processId);
  }

  // --- validation ------------------------------------------------------------------------------

  List<ProcessStep> validateSteps(Process process, List<ProcessStep> steps) {
    List<ProcessStep> clean = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (ProcessStep s : steps) {
      String key = required(s.stepKey(), "Step key").toLowerCase();
      if (!STEP_KEY.matcher(key).matches()) {
        throw new IllegalArgumentException(
            "Step keys are lower case letters, digits, '-' or '_': " + key);
      }
      for (String need : s.needsList()) {
        if (!keys.contains(need)) {
          throw new IllegalArgumentException(
              "Step " + key + " needs '" + need + "', which is not an earlier step");
        }
      }
      if (!keys.add(key)) {
        throw new IllegalArgumentException("Duplicate step key " + key);
      }
      ProcessStepType type = Objects.requireNonNull(s.type(), "Step type");
      String scriptPath = null;
      String target = null;
      Long subProcess = null;
      switch (type) {
        case SCRIPT -> {
          scriptPath = required(s.scriptPath(), "Script of step " + key);
          String lower = scriptPath.toLowerCase();
          if (!lower.endsWith(".py") && !lower.endsWith(".r")) {
            throw new IllegalArgumentException("Scripts are Python (.py) or R (.R): " + scriptPath);
          }
          if (!files.exists(process.folder(), scriptPath)) {
            throw new IllegalArgumentException(
                "Script " + scriptPath + " does not exist in folder " + process.folder());
          }
          if (lower.endsWith(".r") && !runner.rAvailable()) {
            throw new IllegalArgumentException(
                "R scripts need an R runtime (sdmxdw.processes.r-executable)");
          }
        }
        case AGGREGATE -> {
          target = required(s.target(), "Target of step " + key);
          var flow = runner.microdataFlow(target);
          if (flow.linkedDataflowId() == null) {
            throw new IllegalArgumentException(
                target + " has no linked dataflow to aggregate into");
          }
        }
        case PROMOTE -> {
          target = required(s.target(), "Target of step " + key);
          if (runner.dataflow(target).isEmpty()) {
            runner.microdataFlow(target);
          }
        }
        case PROCESS -> {
          subProcess = Objects.requireNonNull(s.subProcessId(), "Sub-process of step " + key);
          if (subProcess.equals(process.id())
              || reaches(subProcess, process.id(), new HashSet<>())) {
            throw new IllegalArgumentException("Sub-process step " + key + " would create a cycle");
          }
        }
      }
      clean.add(
          new ProcessStep(
              null,
              key,
              type,
              scriptPath,
              s.args() == null ? "" : s.args().strip(),
              target,
              subProcess,
              String.join(",", s.needsList())));
    }
    return clean;
  }

  /** Whether process {@code from} (transitively) runs {@code target} as sub-process. */
  private boolean reaches(Long from, Long target, Set<Long> visited) {
    if (!visited.add(from)) {
      return false;
    }
    Process p = processRepository.findById(from).orElse(null);
    if (p == null || p.steps() == null) {
      return false;
    }
    for (ProcessStep s : p.steps()) {
      if (s.subProcessId() != null
          && (s.subProcessId().equals(target) || reaches(s.subProcessId(), target, visited))) {
        return true;
      }
    }
    return false;
  }

  static String describe(ProcessStep s) {
    return switch (s.type()) {
      case SCRIPT ->
          s.scriptPath() + (s.args() == null || s.args().isBlank() ? "" : " " + s.args());
      case AGGREGATE -> "aggregate " + s.target();
      case PROMOTE -> "promote " + s.target();
      case PROCESS -> "process #" + s.subProcessId();
    };
  }

  private static String describeSettingsChange(Process before, Process after) {
    List<String> changes = new ArrayList<>();
    if (!Objects.equals(before.name(), after.name())) {
      changes.add("name: " + before.name() + " -> " + after.name());
    }
    if (!Objects.equals(before.schedule(), after.schedule())) {
      changes.add("schedule: " + before.schedule() + " -> " + after.schedule());
    }
    if (before.enabled() != after.enabled()) {
      changes.add("enabled: " + after.enabled());
    }
    if (before.timeoutMinutes() != after.timeoutMinutes()) {
      changes.add("timeout: " + after.timeoutMinutes() + " min");
    }
    if (!Objects.equals(before.secretNames(), after.secretNames())) {
      changes.add("secrets: " + after.secretNames());
    }
    if (!Objects.equals(before.paramsJson(), after.paramsJson())) {
      changes.add("parameters changed");
    }
    if (!Objects.equals(before.runAfterProcessId(), after.runAfterProcessId())) {
      changes.add("run after: " + after.runAfterProcessId());
    }
    if (!Objects.equals(before.notifyEmail(), after.notifyEmail())
        || !Objects.equals(before.description(), after.description())) {
      changes.add("description/notification changed");
    }
    return changes.isEmpty() ? "no changes" : String.join("; ", changes);
  }

  static String slug(String name) {
    String s =
        java.text.Normalizer.normalize(name.toLowerCase(), java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
    if (s.isEmpty()) {
      s = "process";
    }
    return s.length() > 60 ? s.substring(0, 60) : s;
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String required(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    return value.strip();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
