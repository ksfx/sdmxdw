package de.sdmxdw.microdata;

import static org.assertj.core.api.Assertions.assertThat;

import de.sdmxdw.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

/** Records are stored in one partition per flow and reporting period, created on demand (V19). */
class MicrodataPartitionsTest extends AbstractIntegrationTest {

  @Autowired private MicrodataFlowService flowService;
  @Autowired private MicrodataRecordService recordService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void newPeriodsGetTheirOwnPartition() {
    MicrodataFlow flow =
        flowService.createFlow("TST", "MDF_PART_" + System.nanoTime() % 100000, "1.0.0", "P", null);
    flowService.addField(flow.id(), "X", "X", FieldDataType.STRING, null, false, null);
    recordService.ingestRecord(flow.id(), "E-1", "2026-Q1", Map.of("X", "a"), "t");
    recordService.ingestRecord(flow.id(), "E-1", "2026-Q2", Map.of("X", "b"), "t");
    recordService.ingestRecord(flow.id(), "E-2", "2026-Q2", Map.of("X", "c"), "t");

    assertThat(
            jdbc.queryForList(
                "SELECT reporting_period FROM microdata_partition WHERE microdata_flow_id = ?"
                    + " ORDER BY 1",
                String.class,
                flow.id()))
        .containsExactly("2026-Q1", "2026-Q2");
    // the rows really are in the period partition
    String q2 =
        jdbc.queryForObject(
            "SELECT table_name FROM microdata_partition WHERE microdata_flow_id = ?"
                + " AND reporting_period = '2026-Q2'",
            String.class,
            flow.id());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM " + q2, Long.class)).isEqualTo(2);
    assertThat(recordService.findReportingPeriods(flow.id(), MicrodataStage.ALPHA))
        .containsExactly("2026-Q1", "2026-Q2");
    assertThat(recordService.findReportingPeriods(flow.id(), MicrodataStage.BETA)).isEmpty();
    recordService.promoteToBeta(flow.id(), "dm");
    assertThat(recordService.findReportingPeriods(flow.id(), MicrodataStage.BETA))
        .containsExactly("2026-Q1", "2026-Q2");
  }
}
