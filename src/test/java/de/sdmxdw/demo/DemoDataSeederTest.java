package de.sdmxdw.demo;

import static org.assertj.core.api.Assertions.assertThat;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.microdata.MicrodataStage;
import de.sdmxdw.structure.category.CategorySchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** The optional demo data is consistent and created only once. */
@AutoConfigureMockMvc
class DemoDataSeederTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private CategorySchemeService categorySchemes;

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

  @Test
  @WithMockUser(roles = "OEKONOM")
  void relationGraphCarriesSummariesForTheDetailPanel() throws Exception {
    seeder.seedIfEmpty(); // runs as its own system user
    Long scheme =
        categorySchemes.findAll().stream()
            .filter(c -> c.agencyId().equals("DEMO") && c.artefactId().equals("CS_DEMO_REGISTER"))
            .findFirst()
            .orElseThrow()
            .id();
    String json =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/explore/microdata/category-schemes/" + scheme + "/relations"))
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(json)
        .contains(
            "\"summary\":\"A borrower of the demo bank: a household, a company or a public body.\"")
        .contains("\"summary\":\"Kind of loan.\"")
        .contains("\"fieldCode\":\"BORROWER\"");
  }
}
