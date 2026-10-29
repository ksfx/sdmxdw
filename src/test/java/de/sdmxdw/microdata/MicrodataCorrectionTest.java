package de.sdmxdw.microdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.audit.AuditLogService;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** Manual correction of microdata records (#16), pending changes and promotion (#19). */
class MicrodataCorrectionTest extends AbstractIntegrationTest {

  private static int counter;

  @Autowired private MicrodataFlowService flowService;
  @Autowired private MicrodataRecordService recordService;
  @Autowired private CodelistService codelistService;
  @Autowired private AuditLogService auditLogService;

  /** LOAN flow with an indexed coded CURRENCY, a required AMOUNT and a COLLATERAL group. */
  private MicrodataFlow flow() {
    String suffix = "COR" + (++counter) + "_" + System.nanoTime() % 100000;
    MicrodataFlow flow = flowService.createFlow("TST", "MDF_" + suffix, "1.0.0", "Loans", null);
    Codelist ccy = codelistService.createCodelist("TST", "CL_" + suffix, "1.0.0", "Currency");
    ccy = codelistService.addCode(ccy.id(), "CHF", "Swiss franc");
    ccy = codelistService.addCode(ccy.id(), "EUR", "Euro");
    flowService.addField(
        flow.id(), "CURRENCY", "Currency", FieldDataType.STRING, ccy.id(), true, FacetSlot.facet1);
    flowService.addField(flow.id(), "AMOUNT", "Amount", FieldDataType.NUMBER, null, true, null);
    flowService.addGroupField(
        flow.id(), "COLLATERAL", "TYPE", "Type", FieldDataType.STRING, null, false);
    recordService.ingestRecord(
        flow.id(),
        "L-1",
        "2026-Q3",
        Map.of("CURRENCY", "CHF", "AMOUNT", "100"),
        Map.of("COLLATERAL", List.of(Map.of("TYPE", "RE"))),
        "import");
    recordService.ingestRecord(
        flow.id(), "L-2", "2026-Q3", Map.of("CURRENCY", "EUR", "AMOUNT", "200"), "import");
    recordService.promoteToBeta(flow.id(), "dm");
    return flow;
  }

  @Test
  @WithMockUser(username = "dm@x", roles = "DATENMANAGER")
  void correctionIsValidatedAuditedPendingAndPromotedWithReason() {
    MicrodataFlow flow = flow();
    assertThat(recordService.countPendingChanges(flow.id())).isZero();

    // validation exactly as on ingest: unknown code, missing required field
    assertThatThrownBy(
            () ->
                recordService.correctRecord(
                    flow.id(),
                    "L-1",
                    "2026-Q3",
                    Map.of("CURRENCY", "XXX", "AMOUNT", "1"),
                    Map.of(),
                    null,
                    "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                recordService.correctRecord(
                    flow.id(), "L-1", "2026-Q3", Map.of("CURRENCY", "CHF"), Map.of(), null, "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                recordService.correctRecord(
                    flow.id(),
                    "NOPE",
                    "2026-Q3",
                    Map.of("CURRENCY", "CHF", "AMOUNT", "1"),
                    Map.of(),
                    null,
                    "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);

    MicrodataRecord corrected =
        recordService.correctRecord(
            flow.id(),
            "L-1",
            "2026-Q3",
            Map.of("CURRENCY", "EUR", "AMOUNT", "150"),
            Map.of("COLLATERAL", List.of(Map.of("TYPE", "RE"), Map.of("TYPE", "SEC"))),
            "  bank resubmitted ",
            "dm@x");
    assertThat(corrected.stage()).isEqualTo(MicrodataStage.ALPHA);
    assertThat(corrected.comment()).isEqualTo("bank resubmitted");
    assertThat(corrected.facet1Value()).isEqualTo("EUR");

    var audit = auditLogService.findByMicrodataFlow(flow.id(), 0, 10).entries();
    assertThat(audit.getFirst().action()).isEqualTo("CORRECT_RECORD");
    assertThat(audit.getFirst().details())
        .contains("L-1 @ 2026-Q3")
        .contains("CURRENCY: CHF -> EUR")
        .contains("AMOUNT: 100 -> 150")
        .contains("COLLATERAL: 1 -> 2 entries")
        .contains("(bank resubmitted)");

    // pending: exactly the corrected record, filterable by indexed field
    MicrodataQuery all =
        new MicrodataQuery(flow.id(), MicrodataStage.ALPHA, List.of(), null, Map.of(), 50, 0);
    var pending = recordService.findPendingChanges(all);
    assertThat(pending.total()).isEqualTo(1);
    assertThat(pending.rows().getFirst().alpha().entityId()).isEqualTo("L-1");
    assertThat(pending.rows().getFirst().betaPayloadJson()).contains("\"CHF\"");
    MicrodataQuery chfOnly =
        new MicrodataQuery(
            flow.id(),
            MicrodataStage.ALPHA,
            List.of(),
            null,
            Map.of("CURRENCY", List.of("CHF")),
            50,
            0);
    assertThat(recordService.findPendingChanges(chfOnly).total()).isZero();

    // facets count Beta records; each field's own filter is left out
    var facets =
        recordService.findFieldFacets(
            new MicrodataQuery(
                flow.id(),
                MicrodataStage.BETA,
                List.of(),
                null,
                Map.of("CURRENCY", List.of("CHF")),
                null,
                0));
    assertThat(facets.get("CURRENCY")).containsEntry("CHF", 1L).containsEntry("EUR", 1L);

    recordService.promoteToBeta(flow.id(), "dm@x");
    assertThat(recordService.countPendingChanges(flow.id())).isZero();
    MicrodataRecord beta =
        recordService
            .findCurrentOne(flow.id(), "L-1", "2026-Q3", MicrodataStage.BETA)
            .orElseThrow();
    assertThat(beta.comment()).isEqualTo("bank resubmitted");
    assertThat(recordService.parsePayload(beta.payloadJson()).groups().get("COLLATERAL"))
        .hasSize(2);
    assertThat(auditLogService.findByMicrodataFlow(flow.id(), 0, 10).entries().getFirst().action())
        .isEqualTo("PROMOTE_ALPHA_TO_BETA");
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomCannotCorrectOrSeePendingChanges() {
    assertThatThrownBy(
            () -> recordService.correctRecord(1L, "x", "p", Map.of(), Map.of(), null, "oe"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> recordService.countPendingChanges(1L))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                recordService.findRecordPage(
                    new MicrodataQuery(1L, MicrodataStage.ALPHA, List.of(), null, Map.of(), 10, 0)))
        .isInstanceOf(AccessDeniedException.class);
  }
}
