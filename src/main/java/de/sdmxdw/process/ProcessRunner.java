package de.sdmxdw.process;

import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.security.RunCredentialService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes process runs. Runs are queued per process (at most one active run per process, a small
 * global pool), steps run in their order when all steps they need have succeeded. Script steps get
 * a working directory shared by all steps of the run, parameters, secrets and a read credential as
 * environment variables; whatever a step writes to its output folder is loaded by {@link
 * ProcessLoader} right after the step (all or nothing). Logs are streamed into the step run while
 * the step is running.
 */
@Service
@Order(20)
public class ProcessRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
  private static final int MAX_LOG_CHARS = 2_000_000;
  private static final long LOG_FLUSH_MILLIS = 1500;
  private static final int MAX_SUB_PROCESS_DEPTH = 5;
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-z][a-z0-9_]*)}");

  private final ProcessRepository processRepository;
  private final ProcessRunRepository runRepository;
  private final ProcessStepRunRepository stepRunRepository;
  private final ProcessFiles files;
  private final ProcessLoader loader;
  private final ProcessSecretService secrets;
  private final ProcessGitService git;
  private final ProcessNotificationService notifications;
  private final RunCredentialService runCredentials;
  private final DataflowService dataflowService;
  private final MicrodataFlowService microdataFlowService;
  private final ObservationService observationService;
  private final MicrodataRecordService microdataRecordService;
  private final ObjectMapper objectMapper;
  private final Path runsDir;
  private final String pythonExecutable;
  private final String rExecutable;
  private final String rLibs;
  private final org.springframework.core.env.Environment environment;
  private final ExecutorService pool;

  private final Map<Long, Long> activeRunByProcess = new ConcurrentHashMap<>();
  private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
  private final Map<Long, java.lang.Process> runningOsProcesses = new ConcurrentHashMap<>();

  public ProcessRunner(
      ProcessRepository processRepository,
      ProcessRunRepository runRepository,
      ProcessStepRunRepository stepRunRepository,
      ProcessFiles files,
      ProcessLoader loader,
      ProcessSecretService secrets,
      ProcessGitService git,
      ProcessNotificationService notifications,
      RunCredentialService runCredentials,
      DataflowService dataflowService,
      MicrodataFlowService microdataFlowService,
      ObservationService observationService,
      MicrodataRecordService microdataRecordService,
      ObjectMapper objectMapper,
      @Value("${sdmxdw.processes.runs-dir:data/runs}") String runsDir,
      @Value("${sdmxdw.processes.python-executable:python3}") String pythonExecutable,
      @Value("${sdmxdw.processes.r-executable:}") String rExecutable,
      @Value("${sdmxdw.processes.r-libs:}") String rLibs,
      org.springframework.core.env.Environment environment,
      @Value("${sdmxdw.processes.max-parallel-runs:2}") int maxParallelRuns) {
    this.processRepository = processRepository;
    this.runRepository = runRepository;
    this.stepRunRepository = stepRunRepository;
    this.files = files;
    this.loader = loader;
    this.secrets = secrets;
    this.git = git;
    this.notifications = notifications;
    this.runCredentials = runCredentials;
    this.dataflowService = dataflowService;
    this.microdataFlowService = microdataFlowService;
    this.observationService = observationService;
    this.microdataRecordService = microdataRecordService;
    this.objectMapper = objectMapper;
    this.runsDir = Path.of(runsDir).toAbsolutePath().normalize();
    this.pythonExecutable = pythonExecutable;
    this.rExecutable = rExecutable;
    this.rLibs = rLibs;
    this.environment = environment;
    this.pool =
        Executors.newFixedThreadPool(
            Math.max(1, maxParallelRuns),
            r -> {
              Thread t = new Thread(r, "process-run");
              t.setDaemon(true);
              return t;
            });
  }

  /** Runs left over from a previous JVM cannot be resumed. */
  @Override
  public void run(ApplicationArguments args) {
    for (ProcessRun run : runRepository.findUnfinished()) {
      runRepository.save(
          run.finished(
              run.status() == ProcessRunStatus.QUEUED
                  ? ProcessRunStatus.CANCELLED
                  : ProcessRunStatus.FAILURE,
              "Interrupted by an application restart"));
    }
  }

  @PreDestroy
  void shutdown() {
    runningOsProcesses.values().forEach(java.lang.Process::destroy);
    pool.shutdownNow();
  }

  public boolean rAvailable() {
    return rExecutable != null && !rExecutable.isBlank();
  }

  public Path runsDir() {
    return runsDir;
  }

  /** Queues a run; it starts at once unless the process is already running. */
  public ProcessRun start(
      Long processId,
      ProcessRunTrigger trigger,
      Map<String, String> params,
      boolean dryRun,
      String triggeredBy) {
    Process process = processRepository.findById(processId).orElseThrow();
    Map<String, String> resolved = resolveParams(process, params);
    ProcessRun run =
        runRepository.save(
            ProcessRun.queue(processId, null, trigger, triggeredBy, json(resolved), dryRun));
    submitNext(processId);
    return run;
  }

  public boolean cancel(Long runId) {
    ProcessRun run = runRepository.findById(runId).orElseThrow();
    if (run.status() == ProcessRunStatus.QUEUED) {
      runRepository.save(run.finished(ProcessRunStatus.CANCELLED, "Cancelled before start"));
      return true;
    }
    if (run.status() != ProcessRunStatus.RUNNING) {
      return false;
    }
    cancelFlags.computeIfAbsent(runId, id -> new AtomicBoolean()).set(true);
    java.lang.Process os = runningOsProcesses.get(runId);
    if (os != null) {
      os.descendants().forEach(ProcessHandle::destroy);
      os.destroy();
    }
    return true;
  }

  public boolean isActive(Long processId) {
    return activeRunByProcess.containsKey(processId);
  }

  private synchronized void submitNext(Long processId) {
    if (activeRunByProcess.containsKey(processId)) {
      return;
    }
    Optional<ProcessRun> next = runRepository.findNextQueued(processId);
    if (next.isEmpty()) {
      return;
    }
    activeRunByProcess.put(processId, next.get().id());
    pool.submit(() -> runTopLevel(next.get().id()));
  }

  private void runTopLevel(Long runId) {
    ProcessRun run = runRepository.findById(runId).orElseThrow();
    try {
      ProcessRun finished = execute(run, 0);
      if (finished.status() == ProcessRunStatus.SUCCESS && !finished.dryRun()) {
        for (Process follower : processRepository.findByRunAfterProcessId(run.processId())) {
          if (follower.enabled()) {
            start(follower.id(), ProcessRunTrigger.CHAINED, Map.of(), false, "after run #" + runId);
          }
        }
      }
    } catch (RuntimeException e) {
      log.error("Process run {} crashed", runId, e);
      runRepository
          .findById(runId)
          .filter(r -> !r.status().finished())
          .ifPresent(r -> runRepository.save(r.finished(ProcessRunStatus.FAILURE, e.toString())));
    } finally {
      cancelFlags.remove(runId);
      activeRunByProcess.remove(run.processId());
      submitNext(run.processId());
    }
  }

  /** Executes all steps of a run in the current thread. */
  private ProcessRun execute(ProcessRun queued, int depth) {
    Process process = processRepository.findById(queued.processId()).orElseThrow();
    ProcessRun run = runRepository.save(queued.started(git.currentCommit()));
    String identity = "process:" + process.folder() + "#" + run.id();
    Path workDir = runsDir.resolve(String.valueOf(rootRunId(run)));
    Path outDir = workDir.resolve("out");
    String credential = runCredentials.issue(run.id(), process.folder());
    Instant deadline = Instant.now().plusSeconds(process.timeoutMinutes() * 60L);
    Map<String, String> params = readParams(run.paramsJson());

    List<ProcessStep> steps = process.steps() == null ? List.of() : process.steps();
    List<ProcessStepRun> stepRuns = new ArrayList<>();
    for (int i = 0; i < steps.size(); i++) {
      stepRuns.add(stepRunRepository.save(ProcessStepRun.pending(run.id(), i, steps.get(i))));
    }
    Map<String, ProcessStepRunStatus> results = new HashMap<>();
    List<String> failures = new ArrayList<>();
    boolean cancelled = false;
    try {
      Files.createDirectories(outDir);
      for (int i = 0; i < steps.size(); i++) {
        ProcessStep step = steps.get(i);
        ProcessStepRun stepRun = stepRuns.get(i);
        List<String> needs =
            step.needsList().isEmpty() && i > 0
                ? List.of(steps.get(i - 1).stepKey())
                : step.needsList();
        if (cancelled || isCancelled(run.id())) {
          cancelled = true;
          stepRunRepository.save(stepRun.withStatus(ProcessStepRunStatus.CANCELLED));
          results.put(step.stepKey(), ProcessStepRunStatus.CANCELLED);
          continue;
        }
        if (needs.stream().anyMatch(n -> results.get(n) != ProcessStepRunStatus.SUCCESS)) {
          stepRunRepository.save(
              stepRun
                  .withResult(null, "Skipped: needs " + String.join(", ", needs), null)
                  .withStatus(ProcessStepRunStatus.SKIPPED));
          results.put(step.stepKey(), ProcessStepRunStatus.SKIPPED);
          continue;
        }
        stepRun = stepRunRepository.save(stepRun.withStatus(ProcessStepRunStatus.RUNNING));
        StepOutcome outcome;
        try {
          outcome =
              switch (step.type()) {
                case SCRIPT ->
                    runScript(
                        process,
                        run,
                        step,
                        stepRun,
                        workDir,
                        outDir,
                        params,
                        credential,
                        deadline,
                        identity);
                case AGGREGATE -> runAggregate(step, run, identity);
                case PROMOTE -> runPromote(step, run, identity);
                case PROCESS -> runSubProcess(step, run, depth);
              };
        } catch (RuntimeException e) {
          log.warn("Step {} of run {} failed", step.stepKey(), run.id(), e);
          outcome = StepOutcome.failure(null, "Step failed: " + e.getMessage(), null);
        }
        ProcessStepRunStatus status = outcome.status();
        if (isCancelled(run.id()) && status != ProcessStepRunStatus.SUCCESS) {
          status = ProcessStepRunStatus.CANCELLED;
          cancelled = true;
        }
        stepRunRepository.save(
            stepRun
                .withResult(outcome.exitCode(), outcome.log(), outcome.loadReport())
                .withStatus(status));
        results.put(step.stepKey(), status);
        if (status == ProcessStepRunStatus.FAILURE) {
          failures.add(step.stepKey() + ": " + lastLine(outcome.log()));
        }
      }
    } catch (IOException e) {
      failures.add("Cannot create working directory: " + e.getMessage());
    } finally {
      runCredentials.revoke(credential);
    }
    ProcessRunStatus status =
        cancelled
            ? ProcessRunStatus.CANCELLED
            : failures.isEmpty() ? ProcessRunStatus.SUCCESS : ProcessRunStatus.FAILURE;
    String message =
        cancelled ? "Cancelled" : failures.isEmpty() ? null : String.join("\n", failures);
    ProcessRun finished = runRepository.save(run.finished(status, message));
    if (status == ProcessRunStatus.FAILURE && depth == 0) {
      notifications.notifyFailure(process, finished, message);
    }
    return finished;
  }

  record StepOutcome(ProcessStepRunStatus status, Integer exitCode, String log, String loadReport) {
    static StepOutcome success(Integer exitCode, String log, String loadReport) {
      return new StepOutcome(ProcessStepRunStatus.SUCCESS, exitCode, log, loadReport);
    }

    static StepOutcome failure(Integer exitCode, String log, String loadReport) {
      return new StepOutcome(ProcessStepRunStatus.FAILURE, exitCode, log, loadReport);
    }
  }

  // --- script steps ------------------------------------------------------------------------------

  private StepOutcome runScript(
      Process process,
      ProcessRun run,
      ProcessStep step,
      ProcessStepRun stepRun,
      Path workDir,
      Path outDir,
      Map<String, String> params,
      String credential,
      Instant deadline,
      String identity) {
    Path processDir = files.folderPath(process.folder());
    Path script = files.resolve(process.folder(), step.scriptPath());
    if (!Files.isRegularFile(script)) {
      return StepOutcome.failure(null, "Script not found: " + step.scriptPath(), null);
    }
    List<String> command = new ArrayList<>();
    if (step.isRScript()) {
      if (!rAvailable()) {
        return StepOutcome.failure(
            null, "No R runtime configured (sdmxdw.processes.r-executable)", null);
      }
      command.add(rExecutable);
    } else {
      command.add(pythonExecutable);
      command.add("-u"); // unbuffered: the live log shows output immediately
    }
    command.add(script.toString());
    command.addAll(splitArgs(substitute(step.args(), params)));

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workDir.toFile());
    builder.redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    Path shared = files.folderPath(ProcessFiles.SHARED);
    env.put("PYTHONPATH", processDir + java.io.File.pathSeparator + shared);
    env.put("PYTHONIOENCODING", "utf-8");
    if (rLibs != null && !rLibs.isBlank()) {
      env.put("R_LIBS_USER", rLibs);
    }
    env.put("SDMXDW_RUN_ID", String.valueOf(run.id()));
    env.put("SDMXDW_PROCESS", process.folder());
    env.put("SDMXDW_PROCESS_DIR", processDir.toString());
    env.put("SDMXDW_SHARED_DIR", shared.toString());
    env.put("SDMXDW_WORK_DIR", workDir.toString());
    env.put("SDMXDW_OUT_DIR", outDir.toString());
    env.put("SDMXDW_STEP", step.stepKey());
    env.put("SDMXDW_DRY_RUN", run.dryRun() ? "1" : "0");
    // resolved per run: may refer to local.server.port, known only after startup
    env.put(
        "SDMXDW_API_URL",
        environment.getProperty("sdmxdw.processes.api-url", "http://localhost:8080/rest"));
    env.put("SDMXDW_API_TOKEN", credential);
    params.forEach((k, v) -> env.put("SDMXDW_PARAM_" + k.toUpperCase(), v == null ? "" : v));
    Map<String, String> secretValues = secrets.resolve(process.secretNameList());
    env.putAll(secretValues);

    StringBuilder output = new StringBuilder();
    // the command as the user wrote it: runtime, script relative to the process folder, arguments
    output.append("$ ").append(step.isRScript() ? "Rscript " : "python ").append(step.scriptPath());
    command
        .subList(command.indexOf(script.toString()) + 1, command.size())
        .forEach(a -> output.append(' ').append(a));
    output.append('\n');
    for (String missing : process.secretNameList()) {
      if (!secretValues.containsKey(missing)) {
        output.append("WARNING: secret ").append(missing).append(" is not defined\n");
      }
    }
    java.lang.Process os;
    try {
      os = builder.start();
    } catch (IOException e) {
      return StepOutcome.failure(null, output + "Cannot start: " + e.getMessage(), null);
    }
    runningOsProcesses.put(run.id(), os);
    Thread reader =
        new Thread(() -> pump(os, output, secretValues.values()), "process-log-" + run.id());
    reader.start();
    boolean timedOut = false;
    try {
      long lastFlush = 0;
      while (true) {
        if (os.waitFor(250, TimeUnit.MILLISECONDS)) {
          break;
        }
        if (Instant.now().isAfter(deadline)) {
          timedOut = true;
          os.descendants().forEach(ProcessHandle::destroyForcibly);
          os.destroyForcibly();
          break;
        }
        if (System.currentTimeMillis() - lastFlush > LOG_FLUSH_MILLIS) {
          synchronized (output) {
            stepRunRepository.updateLog(stepRun.id(), output.toString());
          }
          lastFlush = System.currentTimeMillis();
        }
      }
      os.waitFor(5, TimeUnit.SECONDS);
      reader.join(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      os.destroyForcibly();
    } finally {
      runningOsProcesses.remove(run.id());
    }
    String logText;
    synchronized (output) {
      if (timedOut) {
        output.append("\nTimed out after ").append(process.timeoutMinutes()).append(" minutes\n");
      }
      logText = output.toString();
    }
    if (timedOut) {
      return StepOutcome.failure(null, logText, null);
    }
    int exit = os.exitValue();
    if (exit != 0) {
      return StepOutcome.failure(exit, logText + "\nExit code " + exit + "\n", null);
    }

    // file handoff: load what the step wrote, then move the files out of the way
    ProcessLoader.StepLoadReport report =
        ProcessSystemContext.runAs(identity, () -> loader.load(outDir, run.dryRun(), identity));
    String reportJson = json(report);
    StringBuilder summary = new StringBuilder(logText);
    if (!report.files().isEmpty()) {
      summary
          .append("\n--- ")
          .append(run.dryRun() ? "Validated (dry run, nothing loaded)" : "Loaded")
          .append(": ")
          .append(String.join(", ", report.files()))
          .append('\n');
      report
          .targets()
          .forEach(
              t ->
                  summary.append(
                      String.format(
                          "%s: %d rows, %d written, %d unchanged%n",
                          t.flowRef(), t.rows(), t.written(), t.unchanged())));
      report.errors().forEach(e -> summary.append("ERROR ").append(e).append('\n'));
    }
    moveLoaded(outDir, workDir.resolve("loaded").resolve(step.stepKey()));
    return report.failed()
        ? StepOutcome.failure(exit, summary.toString(), reportJson)
        : StepOutcome.success(exit, summary.toString(), reportJson);
  }

  private static void pump(
      java.lang.Process os, StringBuilder output, java.util.Collection<String> secrets) {
    try (BufferedReader in =
        new BufferedReader(new InputStreamReader(os.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        for (String secret : secrets) {
          if (secret.length() >= 4) {
            line = line.replace(secret, "****");
          }
        }
        synchronized (output) {
          if (output.length() < MAX_LOG_CHARS) {
            output.append(line).append('\n');
          } else if (output.length() < MAX_LOG_CHARS + 100) {
            output.append("... (log truncated)\n");
          }
        }
      }
    } catch (IOException ignored) {
      // process ended or was killed
    }
  }

  private static void moveLoaded(Path outDir, Path target) {
    try {
      for (Path file : ProcessLoader.filesIn(outDir)) {
        Path destination = target.resolve(outDir.relativize(file));
        Files.createDirectories(destination.getParent());
        Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      log.warn("Could not move loaded files out of {}", outDir, e);
    }
  }

  // --- built-in steps
  // ------------------------------------------------------------------------------

  private StepOutcome runAggregate(ProcessStep step, ProcessRun run, String identity) {
    MicrodataFlow flow = microdataFlow(step.target());
    if (run.dryRun()) {
      return StepOutcome.success(
          null, "Dry run: aggregation of " + step.target() + " skipped", null);
    }
    MicrodataRecordService.AggregationResult result =
        ProcessSystemContext.runAs(
            identity, () -> microdataRecordService.aggregateAndPublish(flow.id(), identity));
    return StepOutcome.success(
        null,
        "Aggregated "
            + step.target()
            + ": "
            + result.groupsWritten()
            + " groups written, "
            + result.promoted()
            + " observations published",
        null);
  }

  private StepOutcome runPromote(ProcessStep step, ProcessRun run, String identity) {
    if (run.dryRun()) {
      return StepOutcome.success(null, "Dry run: promotion of " + step.target() + " skipped", null);
    }
    Optional<Dataflow> dataflow = dataflow(step.target());
    if (dataflow.isPresent()) {
      ObservationService.PromotionResult result =
          ProcessSystemContext.runAs(
              identity, () -> observationService.promoteToBeta(dataflow.get().id(), identity));
      return StepOutcome.success(
          null,
          "Promoted "
              + step.target()
              + ": "
              + result.promoted()
              + " published, "
              + result.unchanged()
              + " unchanged",
          null);
    }
    MicrodataFlow flow = microdataFlow(step.target());
    MicrodataRecordService.PromotionResult result =
        ProcessSystemContext.runAs(
            identity, () -> microdataRecordService.promoteToBeta(flow.id(), identity));
    return StepOutcome.success(
        null,
        "Promoted "
            + step.target()
            + ": "
            + result.promoted()
            + " published, "
            + result.unchanged()
            + " unchanged",
        null);
  }

  private StepOutcome runSubProcess(ProcessStep step, ProcessRun parent, int depth) {
    if (depth >= MAX_SUB_PROCESS_DEPTH) {
      return StepOutcome.failure(null, "Sub-processes nested too deeply", null);
    }
    Process sub = processRepository.findById(step.subProcessId()).orElseThrow();
    Map<String, String> params = resolveParams(sub, readParams(parent.paramsJson()));
    ProcessRun child =
        runRepository.save(
            ProcessRun.queue(
                sub.id(),
                parent.id(),
                ProcessRunTrigger.SUB_PROCESS,
                "run #" + parent.id(),
                json(params),
                parent.dryRun()));
    ProcessRun finished = execute(child, depth + 1);
    String log =
        "Sub-process '"
            + sub.name()
            + "' run #"
            + finished.id()
            + ": "
            + finished.status()
            + (finished.message() == null ? "" : "\n" + finished.message());
    return finished.status() == ProcessRunStatus.SUCCESS
        ? StepOutcome.success(null, log, null)
        : StepOutcome.failure(null, log, null);
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** Sub-process runs share the working directory of the top-level run. */
  private Long rootRunId(ProcessRun run) {
    ProcessRun current = run;
    while (current.parentRunId() != null) {
      current = runRepository.findById(current.parentRunId()).orElseThrow();
    }
    return current.id();
  }

  private boolean isCancelled(Long runId) {
    ProcessRun run = runRepository.findById(runId).orElse(null);
    Long root = run == null ? runId : rootRunId(run);
    AtomicBoolean flag = cancelFlags.get(root);
    return flag != null && flag.get();
  }

  Map<String, String> resolveParams(Process process, Map<String, String> given) {
    Map<String, String> result = new LinkedHashMap<>();
    for (ProcessParam p : params(process)) {
      String value = given == null ? null : given.get(p.name());
      if (value == null || value.isBlank()) {
        value = p.defaultValue();
      }
      if (p.required() && (value == null || value.isBlank())) {
        throw new IllegalArgumentException("Parameter " + p.name() + " is required");
      }
      result.put(p.name(), value == null ? "" : value.strip());
    }
    return result;
  }

  List<ProcessParam> params(Process process) {
    try {
      return objectMapper.readValue(
          process.paramsJson() == null || process.paramsJson().isBlank()
              ? "[]"
              : process.paramsJson(),
          new TypeReference<List<ProcessParam>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Corrupt parameter definitions of " + process.name(), e);
    }
  }

  private Map<String, String> readParams(String json) {
    try {
      return objectMapper.readValue(
          json == null ? "{}" : json, new TypeReference<Map<String, String>>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String substitute(String args, Map<String, String> params) {
    if (args == null) {
      return "";
    }
    Matcher m = PLACEHOLDER.matcher(args);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(params.getOrDefault(m.group(1), "")));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** Splits on whitespace, honouring single and double quotes. */
  static List<String> splitArgs(String args) {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    boolean inToken = false;
    for (char c : args.toCharArray()) {
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        } else {
          current.append(c);
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
        inToken = true;
      } else if (Character.isWhitespace(c)) {
        if (inToken) {
          result.add(current.toString());
          current.setLength(0);
          inToken = false;
        }
      } else {
        current.append(c);
        inToken = true;
      }
    }
    if (inToken) {
      result.add(current.toString());
    }
    return result;
  }

  private static String lastLine(String log) {
    if (log == null || log.isBlank()) {
      return "failed";
    }
    String[] lines = log.strip().split("\n");
    return lines[lines.length - 1];
  }

  Optional<Dataflow> dataflow(String ref) {
    return dataflowService.findAll().stream()
        .filter(d -> ProcessLoader.ref(d.agencyId(), d.artefactId(), d.version()).equals(ref))
        .findFirst();
  }

  MicrodataFlow microdataFlow(String ref) {
    return microdataFlowService.findAll().stream()
        .filter(f -> ProcessLoader.ref(f.agencyId(), f.artefactId(), f.version()).equals(ref))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown flow " + ref));
  }
}
