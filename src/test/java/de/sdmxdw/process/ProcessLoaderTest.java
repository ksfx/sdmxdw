package de.sdmxdw.process;

import static org.assertj.core.api.Assertions.assertThat;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.data.observation.ObservationStage;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.microdata.MicrodataStage;
import de.sdmxdw.structure.dataflow.Dataflow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** The file handoff: SDMX-CSV / Parquet deliveries loaded set-based into Alpha. */
@WithMockUser(username = "process:test#1", roles = "DATENMANAGER")
class ProcessLoaderTest extends AbstractIntegrationTest {

  @Autowired private ProcessLoader loader;
  @Autowired private ProcessFixtures fixtures;
  @Autowired private ObservationService observationService;
  @Autowired private MicrodataRecordService recordService;

  @TempDir Path out;

  private void write(String name, String content) throws Exception {
    Files.writeString(out.resolve(name), content);
  }

  private void clear() throws Exception {
    try (var files = Files.list(out)) {
      for (Path p : files.toList()) {
        Files.delete(p);
      }
    }
  }

  private int alphaCount(Dataflow df) {
    return observationService.findCurrent(df.id(), ObservationStage.ALPHA).size();
  }

  @Test
  void loadsObservationsAndKeepsUnchangedVintages() throws Exception {
    Dataflow df = fixtures.dataflow(ProcessFixtures.suffix());
    String ref = ProcessFixtures.ref(df);
    write(
        "a.csv",
        "STRUCTURE,STRUCTURE_ID,ACTION,FREQ,TIME_PERIOD,OBS_VALUE\n"
            + "dataflow,"
            + ref
            + ",I,A,2024,1.5\n"
            + "dataflow,"
            + ref
            + ",I,A,2025,2\n"
            + "dataflow,"
            + ref
            + ",I,Q,2025-Q1,3\n");
    var report = loader.load(out, false, "process:test#1");
    assertThat(report.errors()).isEmpty();
    assertThat(report.targets())
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.rows()).isEqualTo(3);
              assertThat(t.written()).isEqualTo(3);
              assertThat(t.kind()).isEqualTo("dataflow");
            });
    assertThat(alphaCount(df)).isEqualTo(3);

    // same delivery again: nothing new; one changed value: one new vintage, history kept
    write(
        "a.csv",
        "STRUCTURE,STRUCTURE_ID,ACTION,FREQ,TIME_PERIOD,OBS_VALUE\n"
            + "dataflow,"
            + ref
            + ",I,A,2024,1.5\n"
            + "dataflow,"
            + ref
            + ",I,A,2025,2.5\n");
    report = loader.load(out, false, "process:test#2");
    assertThat(report.targets().getFirst().written()).isEqualTo(1);
    assertThat(report.targets().getFirst().unchanged()).isEqualTo(1);
    assertThat(alphaCount(df)).isEqualTo(3);
    var series =
        observationService.findSeriesByDataflow(df.id()).stream()
            .filter(s -> s.seriesKey().equals("A"))
            .findFirst()
            .orElseThrow();
    assertThat(observationService.findHistory(series.id(), "2025", ObservationStage.ALPHA))
        .hasSize(2);
  }

  @Test
  void invalidFilesLoadNothingAndDryRunsRollBack() throws Exception {
    Dataflow df = fixtures.dataflow(ProcessFixtures.suffix());
    String ref = ProcessFixtures.ref(df);
    write("ok.csv", "STRUCTURE_ID,FREQ,TIME_PERIOD,OBS_VALUE\n" + ref + ",A,2024,1\n");
    write(
        "bad.csv",
        "STRUCTURE_ID,FREQ,TIME_PERIOD,OBS_VALUE,EXTRA\n"
            + ref
            + ",X,2024,1,\n"
            + ref
            + ",A,,1,\n"
            + ref
            + ",A,2023,abc,\n"
            + "TST:DF_UNKNOWN(1.0),A,2023,1,\n");
    var report = loader.load(out, false, "process:test#1");
    assertThat(report.failed()).isTrue();
    assertThat(report.errors())
        .anyMatch(e -> e.contains("Unknown flow 'TST:DF_UNKNOWN(1.0)'"))
        .noneMatch(e -> e.contains("Unknown code")); // unknown flow stops before validation
    clear();
    write(
        "bad.csv",
        "STRUCTURE_ID,FREQ,TIME_PERIOD,OBS_VALUE,EXTRA\n"
            + ref
            + ",X,2024,1,\n"
            + ref
            + ",A,,1,\n"
            + ref
            + ",A,2023,abc,\n"
            + ref
            + ",A,2022,1,\n"
            + ref
            + ",A,2022,2,\n");
    report = loader.load(out, false, "process:test#1");
    assertThat(report.errors())
        .anyMatch(e -> e.contains("bad.csv row 1: Unknown code 'X' for FREQ"))
        .anyMatch(e -> e.contains("row 2: Missing value for TIME_PERIOD"))
        .anyMatch(e -> e.contains("row 3: OBS_VALUE is not a number"))
        .anyMatch(e -> e.contains("Unknown column EXTRA"))
        .anyMatch(e -> e.contains("row 5: Duplicate observation A @ 2022"));
    assertThat(alphaCount(df)).isZero();

    clear();
    write(
        "ok.csv",
        "STRUCTURE_ID,FREQ,TIME_PERIOD,OBS_VALUE\n" + ref + ",A,2024,1\n" + ref + ",A,2025,2\n");
    report = loader.load(out, true, "process:test#1");
    assertThat(report.dryRun()).isTrue();
    assertThat(report.targets().getFirst().written()).isEqualTo(2);
    assertThat(alphaCount(df)).isZero();
  }

  @Test
  void parquetValuesAreNormalised() throws Exception {
    Dataflow df = fixtures.dataflow(ProcessFixtures.suffix());
    String ref = ProcessFixtures.ref(df);
    Path file = out.resolve("d.parquet");
    try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
        Statement st = c.createStatement()) {
      st.execute(
          "COPY (SELECT '"
              + ref
              + "' AS STRUCTURE_ID, 'A' AS FREQ, '2024' AS TIME_PERIOD, 640000.0::DOUBLE AS OBS_VALUE"
              + " UNION ALL SELECT '"
              + ref
              + "', 'A', '2025', 0.0175::DOUBLE) TO '"
              + file
              + "' (FORMAT parquet)");
    }
    var report = loader.load(out, false, "process:test#1");
    assertThat(report.errors()).isEmpty();
    assertThat(observationService.findCurrent(df.id(), ObservationStage.ALPHA))
        .extracting(o -> o.measureValuesJson())
        .anyMatch(j -> j.contains("\"640000\""))
        .anyMatch(j -> j.contains("\"0.0175\""));
  }

  @Test
  void microdataWithGroupsAndReferencesWithinTheDelivery() throws Exception {
    ProcessFixtures.Micro m = fixtures.microdata(ProcessFixtures.suffix());
    String cp = ProcessFixtures.ref(m.counterparties());
    String loan = ProcessFixtures.ref(m.loans());
    // the loan references a counterparty delivered in the same step
    write(
        "cp.csv",
        "STRUCTURE,STRUCTURE_ID,ACTION,ENTITY_ID,REPORTING_PERIOD,NAME\ndataflow,"
            + cp
            + ",I,CP-1,2026-Q3,Muster AG\n");
    write(
        "loan.csv",
        "STRUCTURE,STRUCTURE_ID,ACTION,ENTITY_ID,REPORTING_PERIOD,CURRENCY,AMOUNT,COUNTERPARTY\n"
            + "dataflow,"
            + loan
            + ",I,L-1,2026-Q3,CHF,1000,CP-1\n"
            + "dataflow,"
            + loan
            + ",I,L-2,2026-Q3,EUR,500,CP-1\n");
    write(
        "coll.csv",
        "STRUCTURE_ID,ENTITY_ID,REPORTING_PERIOD,ITEM_NO,TYPE,VALUE\n"
            + loan
            + "/COLLATERAL,L-1,2026-Q3,2,SEC,150\n"
            + loan
            + "/COLLATERAL,L-1,2026-Q3,1,RE,800\n");
    var report = loader.load(out, false, "process:test#1");
    assertThat(report.errors()).isEmpty();
    assertThat(report.targets())
        .extracting(ProcessLoader.TargetReport::written)
        .containsExactly(1, 2);
    var l1 =
        recordService
            .findCurrentOne(m.loans().id(), "L-1", "2026-Q3", MicrodataStage.ALPHA)
            .orElseThrow();
    assertThat(l1.facet1Value()).isEqualTo("CHF");
    assertThat(recordService.parsePayload(l1.payloadJson()).groups().get("COLLATERAL"))
        .extracting(i -> i.get("TYPE"))
        .containsExactly("RE", "SEC");

    clear();
    write(
        "loan.csv",
        "STRUCTURE_ID,ENTITY_ID,REPORTING_PERIOD,CURRENCY,AMOUNT,COUNTERPARTY\n"
            + loan
            + ",L-3,2026-Q3,USD,1,CP-9\n"
            + loan
            + ",L-4,2026-Q3,CHF,,CP-1\n");
    write(
        "coll.csv",
        "STRUCTURE_ID,ENTITY_ID,REPORTING_PERIOD,TYPE\n" + loan + "/COLLATERAL,L-9,2026-Q3,RE\n");
    report = loader.load(out, false, "process:test#1");
    assertThat(report.errors())
        .anyMatch(e -> e.contains("Group row for L-9 @ 2026-Q3 without its main record"));
    clear();
    write(
        "loan.csv",
        "STRUCTURE_ID,ENTITY_ID,REPORTING_PERIOD,CURRENCY,AMOUNT,COUNTERPARTY\n"
            + loan
            + ",L-3,2026-Q3,USD,1,CP-9\n"
            + loan
            + ",L-4,2026-Q3,CHF,,CP-1\n");
    report = loader.load(out, false, "process:test#1");
    assertThat(report.errors())
        .anyMatch(e -> e.contains("Unknown code 'USD' for CURRENCY"))
        .anyMatch(e -> e.contains("COUNTERPARTY references unknown entity 'CP-9'"))
        .anyMatch(e -> e.contains("Missing required field AMOUNT"));
    assertThat(recordService.findCurrentOne(m.loans().id(), "L-4", "2026-Q3", MicrodataStage.ALPHA))
        .isEmpty();
  }
}
