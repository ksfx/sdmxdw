package de.sdmxdw.demo;

import static org.assertj.core.api.Assertions.assertThat;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.microdata.MicrodataStage;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** The optional demo data is consistent and created only once. */
class DemoDataSeederTest extends AbstractIntegrationTest {

  @Autowired private DemoDataSeeder seeder;
  @Autowired private DataflowService dataflows;
  @Autowired private ObservationService observations;
  @Autowired private MicrodataFlowService microdataFlows;
  @Autowired private MicrodataRecordService records;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void createsTheDemoDataOnce() {
    seeder.seedIfEmpty();
    assertThat(seeder.seedIfEmpty()).isFalse();

    Dataflow df =
        dataflows.findAll().stream()
            .filter(d -> d.agencyId().equals("DEMO") && d.artefactId().equals("DF_DEMO_LOANS"))
            .findFirst()
            .orElseThrow();
    // one correction of the time series and one of the loans wait for promotion
    assertThat(observations.countPendingChanges(df.id())).isEqualTo(1);

    MicrodataFlow loans =
        microdataFlows.findAll().stream()
            .filter(f -> f.agencyId().equals("DEMO") && f.artefactId().equals("MDF_DEMO_LOAN"))
            .findFirst()
            .orElseThrow();
    assertThat(records.countPendingChanges(loans.id())).isEqualTo(1);
    assertThat(records.findReportingPeriods(loans.id(), MicrodataStage.BETA))
        .contains("2026-Q1", "2026-Q2");
    assertThat(microdataFlows.findFields(loans.id()))
        .allSatisfy(f -> assertThat(f.description()).isNotBlank());
  }
}
