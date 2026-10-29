package de.sdmxdw.web.process;

import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.process.CronDescriptions;
import de.sdmxdw.process.Process;
import de.sdmxdw.process.ProcessCodeService;
import de.sdmxdw.process.ProcessFiles;
import de.sdmxdw.process.ProcessGitService;
import de.sdmxdw.process.ProcessLoader;
import de.sdmxdw.process.ProcessParam;
import de.sdmxdw.process.ProcessRun;
import de.sdmxdw.process.ProcessRunStatus;
import de.sdmxdw.process.ProcessSecretService;
import de.sdmxdw.process.ProcessService;
import de.sdmxdw.process.ProcessStep;
import de.sdmxdw.process.ProcessStepRun;
import de.sdmxdw.process.ProcessStepType;
import de.sdmxdw.structure.dataflow.DataflowService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Process pages (#20): list, process page with the tabs Overview (steps), Code (file tree and
 * editor), Runs and Settings, run page with live log, secrets and the shared code. Datenmanager
 * only (URL rule and service rules).
 */
@Controller
@RequestMapping("/processes")
public class ProcessController {

  private static final int RUN_PAGE_SIZE = 25;

  private final ProcessService processService;
  private final ProcessCodeService codeService;
  private final ProcessSecretService secretService;
  private final ProcessGitService gitService;
  private final DataflowService dataflowService;
  private final MicrodataFlowService microdataFlowService;
  private final ObjectMapper objectMapper;

  public ProcessController(
      ProcessService processService,
      ProcessCodeService codeService,
      ProcessSecretService secretService,
      ProcessGitService gitService,
      DataflowService dataflowService,
      MicrodataFlowService microdataFlowService,
      ObjectMapper objectMapper) {
    this.processService = processService;
    this.codeService = codeService;
    this.secretService = secretService;
    this.gitService = gitService;
    this.dataflowService = dataflowService;
    this.microdataFlowService = microdataFlowService;
    this.objectMapper = objectMapper;
  }

  // --- list / create ---------------------------------------------------------------------------

  @GetMapping
  public String list(Model model) {
    List<ProcessRow> rows = new ArrayList<>();
    for (Process p : processService.findAll()) {
      rows.add(
          new ProcessRow(
              p,
              p.scheduled() ? CronDescriptions.describeNextRuns(p.schedule()) : null,
              processService.latestRun(p.id()).orElse(null),
              processService.isRunning(p.id()),
              !processService.params(p).isEmpty()));
    }
    model.addAttribute("rows", rows);
    return "process/list";
  }

  @PostMapping
  public String create(
      @RequestParam String name,
      @RequestParam(required = false) String description,
      @RequestParam(required = false) String folder,
      Authentication auth,
      RedirectAttributes redirect) {
    try {
      Process p = processService.create(name, description, folder, auth.getName());
      redirect.addFlashAttribute("flash", "Process created with a starter script main.py.");
      return "redirect:/processes/" + p.id() + "?tab=code&file=main.py";
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("error", e.getMessage());
      return "redirect:/processes";
    }
  }

  // --- process page ----------------------------------------------------------------------------

  @GetMapping("/{id}")
  public String page(
      @PathVariable Long id,
      @RequestParam(defaultValue = "overview") String tab,
      @RequestParam(required = false) String file,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    Process process = processService.findById(id);
    model.addAttribute("process", process);
    model.addAttribute("tab", tab);
    model.addAttribute("running", processService.isRunning(id));
    model.addAttribute("params", processService.params(process));
    model.addAttribute(
        "nextRuns",
        process.scheduled() ? CronDescriptions.describeNextRuns(process.schedule()) : null);
    switch (tab) {
      case "code" -> codeModel(model, process.folder(), file);
      case "runs" -> {
        model.addAttribute("runs", processService.runs(id, page, RUN_PAGE_SIZE));
        long total = processService.countRuns(id);
        model.addAttribute("runPage", page);
        model.addAttribute("runPages", (int) Math.ceil(total / (double) RUN_PAGE_SIZE));
      }
      case "settings" -> {
        model.addAttribute("secrets", secretService.findAll());
        model.addAttribute(
            "otherProcesses",
            processService.findAll().stream().filter(p -> !p.id().equals(id)).toList());
      }
      default -> overviewModel(model, process);
    }
    return "process/detail";
  }

  private void overviewModel(Model model, Process process) {
    model.addAttribute("tab", "overview");
    List<ProcessFiles.FileEntry> scripts =
        codeService.list(process.folder()).stream()
            .filter(f -> f.language().equals("python") || f.language().equals("r"))
            .toList();
    model.addAttribute("scripts", scripts);
    List<String> flows = new ArrayList<>();
    dataflowService
        .findAll()
        .forEach(d -> flows.add(d.agencyId() + ":" + d.artefactId() + "(" + d.version() + ")"));
    model.addAttribute("dataflowRefs", flows);
    List<String> micro = new ArrayList<>();
    microdataFlowService
        .findAll()
        .forEach(f -> micro.add(f.agencyId() + ":" + f.artefactId() + "(" + f.version() + ")"));
    model.addAttribute("microdataRefs", micro);
    model.addAttribute(
        "aggregatableRefs",
        microdataFlowService.findAll().stream()
            .filter(f -> f.linkedDataflowId() != null)
            .map(f -> f.agencyId() + ":" + f.artefactId() + "(" + f.version() + ")")
            .toList());
    model.addAttribute(
        "otherProcesses",
        processService.findAll().stream().filter(p -> !p.id().equals(process.id())).toList());
    model.addAttribute("stepTypes", ProcessStepType.values());
    model.addAttribute("rAvailable", processService.rAvailable());
    List<Map<String, Object>> nodes = new ArrayList<>();
    List<Map<String, Object>> edges = new ArrayList<>();
    List<ProcessStep> steps = process.steps() == null ? List.of() : process.steps();
    for (int i = 0; i < steps.size(); i++) {
      ProcessStep s = steps.get(i);
      nodes.add(
          Map.of(
              "id",
              s.stepKey(),
              "label",
              s.stepKey() + "\n" + describe(s),
              "group",
              s.type().name()));
      List<String> needs =
          s.needsList().isEmpty() && i > 0 ? List.of(steps.get(i - 1).stepKey()) : s.needsList();
      needs.forEach(n -> edges.add(Map.of("from", n, "to", s.stepKey())));
    }
    model.addAttribute("graphJson", json(Map.of("nodes", nodes, "edges", edges)));
  }

  private void codeModel(Model model, String folder, String file) {
    model.addAttribute("gitLinked", gitService.configured());
    List<ProcessFiles.FileEntry> files = codeService.list(folder);
    model.addAttribute("folder", folder);
    model.addAttribute("files", files);
    String selected = file;
    if (selected == null && !files.isEmpty()) {
      selected =
          files.stream()
              .filter(ProcessFiles.FileEntry::editable)
              .map(ProcessFiles.FileEntry::path)
              .findFirst()
              .orElse(null);
    }
    if (selected != null) {
      final String path = selected;
      ProcessFiles.FileEntry entry =
          files.stream().filter(f -> f.path().equals(path)).findFirst().orElse(null);
      if (entry != null && entry.editable()) {
        model.addAttribute("file", entry);
        model.addAttribute("content", codeService.read(folder, path));
      } else if (entry != null) {
        model.addAttribute("file", entry);
        model.addAttribute("binary", true);
      }
    }
  }

  @PostMapping("/{id}/steps")
  public String saveSteps(
      @PathVariable Long id,
      @RequestParam Map<String, String> form,
      Authentication auth,
      RedirectAttributes redirect) {
    TreeMap<Integer, Map<String, String>> byIndex = indexed(form, "steps");
    List<ProcessStep> steps = new ArrayList<>();
    for (Map<String, String> s : byIndex.values()) {
      if (blank(s.get("stepKey")) && blank(s.get("scriptPath")) && blank(s.get("target"))) {
        continue;
      }
      ProcessStepType type = ProcessStepType.valueOf(s.getOrDefault("type", "SCRIPT"));
      steps.add(
          new ProcessStep(
              null,
              s.get("stepKey"),
              type,
              type == ProcessStepType.SCRIPT ? s.get("scriptPath") : null,
              type == ProcessStepType.SCRIPT ? s.getOrDefault("args", "") : "",
              type == ProcessStepType.AGGREGATE
                  ? s.get("aggregateTarget")
                  : type == ProcessStepType.PROMOTE ? s.get("promoteTarget") : null,
              type == ProcessStepType.PROCESS && !blank(s.get("subProcessId"))
                  ? Long.valueOf(s.get("subProcessId"))
                  : null,
              s.getOrDefault("needs", "")));
    }
    try {
      processService.updateSteps(id, steps, auth.getName());
      redirect.addFlashAttribute("flash", "Steps saved.");
    } catch (IllegalArgumentException | NullPointerException e) {
      redirect.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/processes/" + id;
  }

  @PostMapping("/{id}/settings")
  public String saveSettings(
      @PathVariable Long id,
      @RequestParam String name,
      @RequestParam(required = false) String description,
      @RequestParam(required = false) String schedule,
      @RequestParam(defaultValue = "false") boolean enabled,
      @RequestParam(defaultValue = "30") int timeoutMinutes,
      @RequestParam(required = false) String notifyEmail,
      @RequestParam(required = false) Long runAfterProcessId,
      @RequestParam(required = false) List<String> secretNames,
      @RequestParam Map<String, String> form,
      Authentication auth,
      RedirectAttributes redirect) {
    List<ProcessParam> params = new ArrayList<>();
    for (Map<String, String> p : indexed(form, "params").values()) {
      params.add(
          new ProcessParam(
              p.get("name"),
              p.get("label"),
              p.get("defaultValue"),
              "on".equals(p.get("required"))));
    }
    try {
      processService.updateSettings(
          id,
          name,
          description,
          schedule,
          enabled,
          timeoutMinutes,
          notifyEmail,
          runAfterProcessId,
          secretNames,
          params,
          auth.getName());
      redirect.addFlashAttribute("flash", "Settings saved.");
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/processes/" + id + "?tab=settings";
  }

  @PostMapping("/{id}/toggle")
  public String toggle(
      @PathVariable Long id,
      @RequestParam boolean enabled,
      Authentication auth,
      @RequestParam(required = false) String back) {
    processService.setEnabled(id, enabled, auth.getName());
    return "redirect:"
        + ("list".equals(back) ? "/processes" : "/processes/" + id + "?tab=settings");
  }

  @PostMapping("/{id}/delete")
  public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes redirect) {
    try {
      processService.delete(id, auth.getName());
      redirect.addFlashAttribute("flash", "Process deleted. Its folder with the code was kept.");
      return "redirect:/processes";
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("error", e.getMessage());
      return "redirect:/processes/" + id + "?tab=settings";
    }
  }

  // --- runs ------------------------------------------------------------------------------------

  @GetMapping("/{id}/run")
  public String runForm(@PathVariable Long id, Model model) {
    Process process = processService.findById(id);
    model.addAttribute("process", process);
    model.addAttribute("params", processService.params(process));
    return "process/run-form";
  }

  @PostMapping("/{id}/run")
  public String run(
      @PathVariable Long id,
      @RequestParam(defaultValue = "false") boolean dryRun,
      @RequestParam Map<String, String> form,
      Authentication auth,
      RedirectAttributes redirect) {
    Map<String, String> params = new LinkedHashMap<>();
    form.forEach(
        (k, v) -> {
          if (k.startsWith("param.")) {
            params.put(k.substring(6), v);
          }
        });
    try {
      ProcessRun run = processService.startRun(id, params, dryRun, auth.getName());
      return "redirect:/processes/" + id + "/runs/" + run.id();
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("error", e.getMessage());
      return "redirect:/processes/" + id + "/run";
    }
  }

  @GetMapping("/{id}/runs/{runId}")
  public String runPage(@PathVariable Long id, @PathVariable Long runId, Model model) {
    model.addAttribute("process", processService.findById(id));
    runModel(model, runId);
    return "process/run";
  }

  /** Polled by the run page while the run is active. */
  @GetMapping("/{id}/runs/{runId}/live")
  public String runLive(@PathVariable Long id, @PathVariable Long runId, Model model) {
    model.addAttribute("process", processService.findById(id));
    runModel(model, runId);
    return "process/run :: live";
  }

  @PostMapping("/{id}/runs/{runId}/cancel")
  public String cancel(@PathVariable Long id, @PathVariable Long runId) {
    processService.cancelRun(runId);
    return "redirect:/processes/" + id + "/runs/" + runId;
  }

  private void runModel(Model model, Long runId) {
    ProcessRun run = processService.findRun(runId);
    List<StepView> steps = new ArrayList<>();
    for (ProcessStepRun s : processService.stepRuns(runId)) {
      steps.add(new StepView(s, report(s.loadReport())));
    }
    model.addAttribute("run", run);
    model.addAttribute("steps", steps);
    model.addAttribute("subRuns", processService.subRuns(runId));
    model.addAttribute(
        "active",
        run.status() == ProcessRunStatus.QUEUED || run.status() == ProcessRunStatus.RUNNING);
    model.addAttribute("runParams", readMap(run.paramsJson()));
  }

  private ProcessLoader.StepLoadReport report(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, ProcessLoader.StepLoadReport.class);
    } catch (Exception e) {
      return null;
    }
  }

  // --- code ------------------------------------------------------------------------------------

  @PostMapping("/{id}/files")
  public String saveFile(
      @PathVariable Long id,
      @RequestParam String path,
      @RequestParam String content,
      @RequestParam(required = false) String message,
      Authentication auth,
      RedirectAttributes redirect) {
    Process p = processService.findById(id);
    return fileAction(
        redirect,
        "/processes/" + id + "?tab=code",
        path,
        () -> codeService.save(p.folder(), path, content, message, auth.getName()),
        "Saved");
  }

  @PostMapping("/{id}/files/new")
  public String newFile(
      @PathVariable Long id,
      @RequestParam String path,
      Authentication auth,
      RedirectAttributes redirect) {
    Process p = processService.findById(id);
    return fileAction(
        redirect,
        "/processes/" + id + "?tab=code",
        path,
        () -> codeService.create(p.folder(), path.strip(), auth.getName()),
        "Created");
  }

  @PostMapping("/{id}/files/delete")
  public String deleteFile(
      @PathVariable Long id,
      @RequestParam String path,
      Authentication auth,
      RedirectAttributes redirect) {
    Process p = processService.findById(id);
    return fileAction(
        redirect,
        "/processes/" + id + "?tab=code",
        null,
        () -> codeService.delete(p.folder(), path, auth.getName()),
        "Deleted " + path);
  }

  @PostMapping("/{id}/files/rename")
  public String renameFile(
      @PathVariable Long id,
      @RequestParam String path,
      @RequestParam String newPath,
      Authentication auth,
      RedirectAttributes redirect) {
    Process p = processService.findById(id);
    return fileAction(
        redirect,
        "/processes/" + id + "?tab=code",
        newPath.strip(),
        () -> codeService.rename(p.folder(), path, newPath.strip(), auth.getName()),
        "Renamed");
  }

  // shared code (SDKs and helpers used by all processes)

  @GetMapping("/shared")
  public String shared(@RequestParam(required = false) String file, Model model) {
    codeModel(model, ProcessFiles.SHARED, file);
    return "process/shared";
  }

  @PostMapping("/shared/files")
  public String saveSharedFile(
      @RequestParam String path,
      @RequestParam String content,
      @RequestParam(required = false) String message,
      Authentication auth,
      RedirectAttributes redirect) {
    return fileAction(
        redirect,
        "/processes/shared?x=1",
        path,
        () -> codeService.save(ProcessFiles.SHARED, path, content, message, auth.getName()),
        "Saved");
  }

  @PostMapping("/shared/files/new")
  public String newSharedFile(
      @RequestParam String path, Authentication auth, RedirectAttributes redirect) {
    return fileAction(
        redirect,
        "/processes/shared?x=1",
        path.strip(),
        () -> codeService.create(ProcessFiles.SHARED, path.strip(), auth.getName()),
        "Created");
  }

  @PostMapping("/shared/files/delete")
  public String deleteSharedFile(
      @RequestParam String path, Authentication auth, RedirectAttributes redirect) {
    return fileAction(
        redirect,
        "/processes/shared?x=1",
        null,
        () -> codeService.delete(ProcessFiles.SHARED, path, auth.getName()),
        "Deleted " + path);
  }

  private interface FileOp {
    String run();
  }

  private String fileAction(
      RedirectAttributes redirect, String base, String path, FileOp op, String label) {
    try {
      String commit = op.run();
      redirect.addFlashAttribute(
          "flash",
          label + (commit == null ? "." : " and pushed (commit " + commit.substring(0, 8) + ")."));
    } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
      redirect.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:"
        + base
        + (path == null
            ? ""
            : "&file=" + UriUtils.encodeQueryParam(path, java.nio.charset.StandardCharsets.UTF_8));
  }

  // --- git ------------------------------------------------------------------------------------

  @GetMapping("/git")
  public String git(Model model) {
    model.addAttribute("status", gitService.status());
    return "process/git";
  }

  @PostMapping("/git")
  public String connect(
      @RequestParam String repoUrl,
      @RequestParam(required = false) String branch,
      @RequestParam(required = false) String subFolder,
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String token,
      Authentication auth,
      RedirectAttributes redirect) {
    try {
      gitService.connect(repoUrl, branch, subFolder, username, token, auth.getName());
      redirect.addFlashAttribute("flash", "Process folder linked to " + repoUrl.strip() + ".");
    } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
      redirect.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/processes/git";
  }

  @PostMapping("/git/pull")
  public String pull(Authentication auth, RedirectAttributes redirect) {
    try {
      redirect.addFlashAttribute("flash", gitService.pull(auth.getName()));
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/processes/git";
  }

  @PostMapping("/git/reset")
  public String resetToRemote(Authentication auth, RedirectAttributes redirect) {
    try {
      gitService.resetToRemote(auth.getName());
      redirect.addFlashAttribute("flash", "Local changes discarded, now at the remote state.");
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/processes/git";
  }

  @PostMapping("/git/disconnect")
  public String disconnect(Authentication auth, RedirectAttributes redirect) {
    gitService.disconnect(auth.getName());
    redirect.addFlashAttribute(
        "flash", "Unlinked. The code was copied back into the local process folder.");
    return "redirect:/processes/git";
  }

  @GetMapping({"/{id}/history", "/shared/history"})
  public String history(
      @PathVariable(required = false) Long id,
      @RequestParam String file,
      @RequestParam(required = false) String commit,
      Model model) {
    String folder = id == null ? ProcessFiles.SHARED : processService.findById(id).folder();
    var commits = gitService.history(folder + "/" + file, 50);
    String selected = commit != null ? commit : commits.isEmpty() ? null : commits.getFirst().id();
    model.addAttribute("process", id == null ? null : processService.findById(id));
    model.addAttribute("file", file);
    model.addAttribute("commits", commits);
    model.addAttribute("selected", selected);
    model.addAttribute(
        "diff", selected == null ? null : gitService.diff(selected, folder + "/" + file));
    model.addAttribute(
        "backUrl",
        id == null
            ? "/processes/shared?x=1&file=" + file
            : "/processes/" + id + "?tab=code&file=" + file);
    return "process/history";
  }

  // --- secrets ---------------------------------------------------------------------------------

  @GetMapping("/secrets")
  public String secrets(Model model) {
    model.addAttribute("secrets", secretService.findAll());
    return "process/secrets";
  }

  @PostMapping("/secrets")
  public String setSecret(
      @RequestParam String name,
      @RequestParam String value,
      Authentication auth,
      RedirectAttributes redirect) {
    try {
      secretService.set(name.strip(), value, auth.getName());
      redirect.addFlashAttribute("flash", "Secret " + name.strip() + " saved.");
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("error", e.getMessage());
    }
    return "redirect:/processes/secrets";
  }

  @PostMapping("/secrets/delete")
  public String deleteSecret(@RequestParam String name, RedirectAttributes redirect) {
    secretService.delete(name);
    redirect.addFlashAttribute("flash", "Secret " + name + " deleted.");
    return "redirect:/processes/secrets";
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** prefix[0].field=value -> {0: {field: value}} */
  private static TreeMap<Integer, Map<String, String>> indexed(
      Map<String, String> form, String prefix) {
    TreeMap<Integer, Map<String, String>> result = new TreeMap<>();
    form.forEach(
        (k, v) -> {
          if (k.startsWith(prefix + "[")) {
            int close = k.indexOf(']');
            int dot = k.indexOf('.', close);
            if (close > 0 && dot > close) {
              try {
                int index = Integer.parseInt(k.substring(prefix.length() + 1, close));
                result
                    .computeIfAbsent(index, i -> new LinkedHashMap<>())
                    .put(k.substring(dot + 1), v);
              } catch (NumberFormatException ignored) {
                // not an indexed field
              }
            }
          }
        });
    return result;
  }

  private Map<String, String> readMap(String json) {
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

  static String describe(ProcessStep s) {
    return switch (s.type()) {
      case SCRIPT -> s.scriptPath();
      case AGGREGATE -> "aggregate " + s.target();
      case PROMOTE -> "promote " + s.target();
      case PROCESS -> "sub-process #" + s.subProcessId();
    };
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  public record ProcessRow(
      Process process, String nextRuns, ProcessRun lastRun, boolean running, boolean hasParams) {}

  public record StepView(ProcessStepRun step, ProcessLoader.StepLoadReport report) {}

  public static List<String> splitCsv(String s) {
    return s == null || s.isBlank()
        ? List.of()
        : Arrays.stream(s.split(",")).map(String::trim).toList();
  }
}
