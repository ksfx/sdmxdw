package de.sdmxdw.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.data.observation.ObservationStage;
import de.sdmxdw.structure.dataflow.Dataflow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Runs real Python / R steps against a running server (read API via the run credential). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProcessRunnerTest extends AbstractIntegrationTest {

  @Autowired private ProcessService processService;
  @Autowired private ProcessFiles files;
  @Autowired private ProcessRunRepository runRepository;
  @Autowired private ProcessStepRunRepository stepRunRepository;
  @Autowired private ProcessSecretService secretService;
  @Autowired private ProcessFixtures fixtures;
  @Autowired private ObservationService observationService;

  private static <T> T dm(Supplier<T> action) {
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            "dm@x", null, List.of(new SimpleGrantedAuthority("ROLE_DATENMANAGER"))));
    SecurityContextHolder.setContext(context);
    try {
      return action.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  private Process process(String name, Map<String, String> scripts, List<ProcessStep> steps) {
    return dm(
        () -> {
          Process p = processService.create(name, null, null, "dm@x");
          scripts.forEach((path, content) -> files.write(p.folder(), path, content));
          return processService.updateSteps(p.id(), steps, "dm@x");
        });
  }

  private static ProcessStep script(String key, String path, String args, String needs) {
    return new ProcessStep(null, key, ProcessStepType.SCRIPT, path, args, null, null, needs);
  }

  private ProcessRun await(Long runId) {
    Instant until = Instant.now().plus(Duration.ofSeconds(90));
    while (Instant.now().isBefore(until)) {
      ProcessRun run = runRepository.findById(runId).orElseThrow();
      if (run.status().finished()) {
        return run;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("Run " + runId + " did not finish");
  }

  private Map<String, ProcessStepRun> steps(Long runId) {
    Map<String, ProcessStepRun> result = new java.util.LinkedHashMap<>();
    stepRunRepository.findByRunIdOrderByPosition(runId).forEach(s -> result.put(s.stepKey(), s));
    return result;
  }

  @Test
  void stepsHandOverFilesReadViaApiAndPromote() {
    String sfx = ProcessFixtures.suffix();
    Dataflow df = dm(() -> fixtures.dataflow(sfx));
    String ref = ProcessFixtures.ref(df);
    dm(
        () -> {
          secretService.set("TEST_SOURCE_KEY", "very-secret-123", "dm@x");
          return null;
        });
    Process p =
        process(
            "Runner test " + sfx,
            Map.of(
                "lib/values.py",
                "def value_for(year):\n    return str(int(year) * 2)\n",
                "load.py",
                """
                import os, sys
                from sdmxdw_sdk import IngestClient, param
                from lib.values import value_for
                print("key is", os.environ["TEST_SOURCE_KEY"])
                year = param("year")
                IngestClient(sys.argv[2]).send([
                    {"dimensions": {"FREQ": "A", "TIME_PERIOD": year}, "measures": {"OBS_VALUE": value_for(year)}},
                    {"dimensions": {"FREQ": "Q", "TIME_PERIOD": year + "-Q1"}, "measures": {"OBS_VALUE": "1"}},
                ])
                """,
                "check.py",
                """
                import sys
                from sdmxdw_sdk import read_data
                rows = read_data(sys.argv[1], stage="ALPHA")
                print("read", len(rows), "rows:", sorted(r["TIME_PERIOD"] for r in rows))
                sys.exit(0 if len(rows) == 2 else 3)
                """),
            List.of(
                script("load", "load.py", "--flow " + ref, ""),
                script("check", "check.py", ref, ""),
                new ProcessStep(
                    null, "publish", ProcessStepType.PROMOTE, null, "", ref, null, "")));
    dm(
        () ->
            processService.updateSettings(
                p.id(),
                p.name(),
                null,
                null,
                true,
                5,
                null,
                null,
                List.of("TEST_SOURCE_KEY"),
                List.of(new ProcessParam("year", "Year", "2024", true)),
                "dm@x"));

    ProcessRun run =
        await(
            dm(() -> processService.startRun(p.id(), Map.of("year", "2030"), false, "dm@x")).id());
    Map<String, ProcessStepRun> steps = steps(run.id());
    assertThat(run.status()).as(steps.toString()).isEqualTo(ProcessRunStatus.SUCCESS);
    assertThat(steps.get("load").log())
        .contains("key is ****")
        .doesNotContain("very-secret-123")
        .contains(ref + ": 2 rows, 2 written, 0 unchanged");
    assertThat(steps.get("load").loadReport()).contains("\"written\":2");
    assertThat(steps.get("check").log()).contains("read 2 rows: ['2030', '2030-Q1']");
    assertThat(steps.get("publish").log()).contains("2 published");
    assertThat(dm(() -> observationService.findCurrent(df.id(), ObservationStage.BETA)))
        .extracting(o -> o.measureValuesJson())
        .anyMatch(j -> j.contains("\"4060\""));
    assertThat(run.paramsJson()).contains("2030");

    // dry run: same scripts, nothing loaded, promotion skipped
    ProcessRun dry =
        await(dm(() -> processService.startRun(p.id(), Map.of("year", "2031"), true, "dm@x")).id());
    assertThat(steps(dry.id()).get("load").log()).contains("Validated (dry run, nothing loaded)");
    // the check step only sees the data of the real run: 2031 was validated, not loaded
    assertThat(steps(dry.id()).get("check").log()).contains("read 2 rows: ['2030', '2030-Q1']");
    assertThat(dm(() -> observationService.findCurrent(df.id(), ObservationStage.ALPHA)))
        .hasSize(2);
  }

  @Test
  void failuresSkipDependentStepsAndInvalidDeliveriesLoadNothing() {
    String sfx = ProcessFixtures.suffix();
    Dataflow df = dm(() -> fixtures.dataflow(sfx));
    String ref = ProcessFixtures.ref(df);
    Process p =
        process(
            "Failure test " + sfx,
            Map.of(
                "ok.py",
                "print('fine')\n",
                "bad.py",
                "from sdmxdw_sdk import IngestClient\n"
                    + "IngestClient('"
                    + ref
                    + "').send([{'dimensions': {'FREQ': 'X', 'TIME_PERIOD': '2024'}, 'measures': {'OBS_VALUE': '1'}}])\n"),
            List.of(
                script("first", "ok.py", "", ""),
                script("bad", "bad.py", "", "first"),
                script("after", "ok.py", "", ""),
                script("independent", "ok.py", "", "first")));
    ProcessRun run = await(dm(() -> processService.startRun(p.id(), Map.of(), false, "dm@x")).id());
    Map<String, ProcessStepRun> steps = steps(run.id());
    assertThat(run.status()).isEqualTo(ProcessRunStatus.FAILURE);
    assertThat(run.message()).contains("bad:");
    assertThat(steps.get("bad").status()).isEqualTo(ProcessStepRunStatus.FAILURE);
    assertThat(steps.get("bad").log()).contains("Unknown code 'X' for FREQ");
    assertThat(steps.get("after").status()).isEqualTo(ProcessStepRunStatus.SKIPPED);
    assertThat(steps.get("independent").status()).isEqualTo(ProcessStepRunStatus.SUCCESS);
    assertThat(dm(() -> observationService.findCurrent(df.id(), ObservationStage.ALPHA))).isEmpty();
  }

  @Test
  void runsCanBeCancelled() {
    Process p =
        process(
            "Cancel test " + ProcessFixtures.suffix(),
            Map.of("slow.py", "import time\nprint('sleeping', flush=True)\ntime.sleep(60)\n"),
            List.of(script("slow", "slow.py", "", ""), script("next", "slow.py", "", "")));
    Long runId = dm(() -> processService.startRun(p.id(), Map.of(), false, "dm@x")).id();
    Instant until = Instant.now().plusSeconds(20);
    while (Instant.now().isBefore(until)
        && (steps(runId).get("slow") == null
            || !steps(runId).get("slow").log().contains("sleeping"))) {
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }
    assertThat(steps(runId).get("slow").log()).as("live log").contains("sleeping");
    dm(() -> processService.cancelRun(runId));
    ProcessRun run = await(runId);
    assertThat(run.status()).isEqualTo(ProcessRunStatus.CANCELLED);
    assertThat(Duration.between(run.startedAt(), run.finishedAt()))
        .isLessThan(Duration.ofSeconds(30));
    assertThat(steps(runId).get("next").status()).isEqualTo(ProcessStepRunStatus.CANCELLED);
  }

  @Test
  void rStepsReadParametersAndWriteDeliveries() {
    assumeTrue(processService.rAvailable(), "R not configured");
    String sfx = ProcessFixtures.suffix();
    Dataflow df = dm(() -> fixtures.dataflow(sfx));
    String ref = ProcessFixtures.ref(df);
    Process p =
        process(
            "R test " + sfx,
            Map.of(
                "lib.R",
                "double_it <- function(x) 2 * x\n",
                "estimate.R",
                """
                source(file.path(Sys.getenv("SDMXDW_SHARED_DIR"), "sdmxdw.R"))
                sdmxdw_source("lib.R")
                year <- sdmxdw_param("year", "2020")
                df <- data.frame(FREQ = "A", TIME_PERIOD = year, OBS_VALUE = double_it(21))
                sdmxdw_write(commandArgs(trailingOnly = TRUE)[1], df)
                cat("R wrote", nrow(df), "row\\n")
                """),
            List.of(
                new ProcessStep(
                    null, "estimate", ProcessStepType.SCRIPT, "estimate.R", ref, null, null, "")));
    dm(
        () ->
            processService.updateSettings(
                p.id(),
                p.name(),
                null,
                null,
                true,
                5,
                null,
                null,
                List.of(),
                List.of(new ProcessParam("year", null, "2027", false)),
                "dm@x"));
    ProcessRun run = await(dm(() -> processService.startRun(p.id(), Map.of(), false, "dm@x")).id());
    ProcessStepRun step = steps(run.id()).get("estimate");
    assertThat(run.status()).as(step.log()).isEqualTo(ProcessRunStatus.SUCCESS);
    assertThat(step.log()).contains("R wrote 1 row").contains("1 written");
    assertThat(dm(() -> observationService.findCurrent(df.id(), ObservationStage.ALPHA)))
        .singleElement()
        .satisfies(
            o -> {
              assertThat(o.timePeriod()).isEqualTo("2027");
              assertThat(o.measureValuesJson()).contains("\"42\"");
            });
  }

  @Test
  void subProcessesRunInsideAndFollowersAreChained() {
    String sfx = ProcessFixtures.suffix();
    Process child =
        process(
            "Child " + sfx,
            Map.of("c.py", "print('child ran')\n"),
            List.of(script("c", "c.py", "", "")));
    Process parent =
        process(
            "Parent " + sfx,
            Map.of("p.py", "print('parent ran')\n"),
            List.of(
                script("before", "p.py", "", ""),
                new ProcessStep(
                    null, "child", ProcessStepType.PROCESS, null, "", null, child.id(), "")));
    Process follower =
        process(
            "Follower " + sfx,
            Map.of("f.py", "print('follower ran')\n"),
            List.of(script("f", "f.py", "", "")));
    dm(
        () ->
            processService.updateSettings(
                follower.id(),
                follower.name(),
                null,
                null,
                true,
                5,
                null,
                parent.id(),
                List.of(),
                List.of(),
                "dm@x"));

    ProcessRun run =
        await(dm(() -> processService.startRun(parent.id(), Map.of(), false, "dm@x")).id());
    assertThat(run.status()).isEqualTo(ProcessRunStatus.SUCCESS);
    assertThat(steps(run.id()).get("child").log())
        .contains("Sub-process 'Child " + sfx + "'")
        .contains("SUCCESS");
    List<ProcessRun> subRuns = runRepository.findByParentRunIdOrderByQueuedAt(run.id());
    assertThat(subRuns)
        .singleElement()
        .satisfies(r -> assertThat(r.processId()).isEqualTo(child.id()));

    Instant until = Instant.now().plusSeconds(30);
    ProcessRun chained = null;
    while (Instant.now().isBefore(until) && chained == null) {
      chained =
          runRepository.findLatest(follower.id()).filter(r -> r.status().finished()).orElse(null);
    }
    assertThat(chained).isNotNull();
    assertThat(chained.trigger()).isEqualTo(ProcessRunTrigger.CHAINED);
    assertThat(chained.status()).isEqualTo(ProcessRunStatus.SUCCESS);
  }
}
