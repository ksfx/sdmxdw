package de.sdmxdw.microdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.audit.AuditLogService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** Creating and deleting microdata records in the explorer (#25). */
class MicrodataDeletionTest extends AbstractIntegrationTest {

  private static int counter;

  @Autowired private MicrodataFlowService flowService;
  @Autowired private MicrodataRecordService recordService;
  @Autowired private AuditLogService auditLogService;

  private record Flows(MicrodataFlow counterparties, MicrodataFlow loans) {}

  /** Counterparties CP-1, CP-2 and a loan L-1 referencing CP-1, all published (2026-Q3). */
  private Flows flows() {
    String suffix = "DEL" + (++counter) + "_" + System.nanoTime() % 100000;
    MicrodataFlow cp =
        flowService.createFlow("TST", "MDF_CP_" + suffix, "1.0.0", "Counterparties", null);
    flowService.addField(cp.id(), "NAME", "Name", FieldDataType.STRING, null, true, null);
    MicrodataFlow loans = flowService.createFlow("TST", "MDF_L_" + suffix, "1.0.0", "Loans", null);
    flowService.addReferenceField(loans.id(), "DEBTOR", "Debtor", cp.id(), true, null);
    flowService.addField(loans.id(), "AMOUNT", "Amount", FieldDataType.NUMBER, null, true, null);
    recordService.ingestRecord(cp.id(), "CP-1", "2026-Q3", Map.of("NAME", "Alpha AG"), "import");
    recordService.ingestRecord(cp.id(), "CP-2", "2026-Q3", Map.of("NAME", "Beta AG"), "import");
    recordService.ingestRecord(
        loans.id(), "L-1", "2026-Q3", Map.of("DEBTOR", "CP-1", "AMOUNT", "100"), "import");
    recordService.promoteToBeta(cp.id(), "dm");
    recordService.promoteToBeta(loans.id(), "dm");
    return new Flows(cp, loans);
  }

  private static MicrodataQuery alpha(Long flowId) {
    return new MicrodataQuery(flowId, MicrodataStage.ALPHA, List.of(), null, Map.of(), 50, 0);
  }

  @Test
  @WithMockUser(username = "dm@x", roles = "DATENMANAGER")
  void deletionIsPendingUntilPromotionAndKeepsTheHistory() {
    Flows f = flows();
    Long cp = f.counterparties().id();

    assertThatThrownBy(() -> recordService.deleteRecord(cp, "CP-2", "2026-Q3", " ", "dm@x"))
        .hasMessageContaining("reason is required");
    // still referenced by the loan in the same period
    assertThatThrownBy(() -> recordService.deleteRecord(cp, "CP-1", "2026-Q3", "dup", "dm@x"))
        .hasMessageContaining("still referenced")
        .hasMessageContaining(f.loans().artefactId());

    Instant beforeDeletion = Instant.now();
    assertThat(recordService.deleteRecord(cp, "CP-2", "2026-Q3", "reported twice", "dm@x"))
        .isTrue();
    assertThat(recordService.findCurrentOne(cp, "CP-2", "2026-Q3", MicrodataStage.ALPHA)).isEmpty();
    // published until the promotion, listed as a pending deletion
    assertThat(recordService.findCurrentOne(cp, "CP-2", "2026-Q3", MicrodataStage.BETA))
        .isPresent();
    var pending = recordService.findPendingChanges(alpha(cp));
    assertThat(pending.total()).isEqualTo(1);
    assertThat(pending.rows().getFirst().deleted()).isTrue();
    assertThat(pending.rows().getFirst().alpha().entityId()).isEqualTo("CP-2");
    assertThat(pending.rows().getFirst().alpha().deleteReason()).isEqualTo("reported twice");
    assertThat(pending.rows().getFirst().alpha().deletedBy()).isEqualTo("dm@x");
    // filters apply to pending deletions as well (the Beta record is matched)
    assertThat(
            recordService
                .findPendingChanges(
                    new MicrodataQuery(
                        cp, MicrodataStage.ALPHA, List.of(), null, Map.of(), "CP-2", 50, 0))
                .total())
        .isEqualTo(1);
    assertThat(auditLogService.findByMicrodataFlow(cp, 0, 1).entries().getFirst().details())
        .contains("CP-2 @ 2026-Q3")
        .contains("reported twice");

    var promotion = recordService.promoteToBeta(cp, "dm@x");
    assertThat(promotion.deleted()).isEqualTo(1);
    assertThat(promotion.promoted()).isZero();
    assertThat(recordService.findCurrentOne(cp, "CP-2", "2026-Q3", MicrodataStage.BETA)).isEmpty();
    assertThat(recordService.countPendingChanges(cp)).isZero();
    // nothing removed: the history keeps both vintages, "as of" before the deletion still has it
    assertThat(recordService.findHistory(cp, "CP-2", "2026-Q3", MicrodataStage.ALPHA))
        .singleElement()
        .satisfies(r -> assertThat(r.deleted()).isTrue());
    assertThat(
            recordService.countRecords(
                new MicrodataQuery(
                    cp, MicrodataStage.BETA, List.of(), beforeDeletion, Map.of(), null, 0)))
        .isEqualTo(2);
  }

  @Test
  @WithMockUser(username = "dm@x", roles = "DATENMANAGER")
  void recordsAreCreatedAndADeletionIsUndoneByWritingAgain() {
    Flows f = flows();
    Long cp = f.counterparties().id();

    assertThatThrownBy(
            () ->
                recordService.createRecord(
                    cp, "CP-1", "2026-Q3", Map.of("NAME", "x"), Map.of(), null, "dm@x"))
        .hasMessageContaining("exists already");
    assertThatThrownBy(
            () ->
                recordService.createRecord(
                    cp, " ", "2026-Q3", Map.of("NAME", "x"), Map.of(), null, "dm@x"))
        .hasMessageContaining("Entity id is required");
    // validated like a delivery
    assertThatThrownBy(
            () ->
                recordService.createRecord(cp, "CP-3", "2026-Q3", Map.of(), Map.of(), null, "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);

    MicrodataRecord created =
        recordService.createRecord(
            cp, " CP-3 ", "2026-Q4", Map.of("NAME", "Gamma AG"), Map.of(), "late", "dm@x");
    assertThat(created.entityId()).isEqualTo("CP-3");
    assertThat(created.stage()).isEqualTo(MicrodataStage.ALPHA);
    assertThat(recordService.countPendingChanges(cp)).isEqualTo(1);

    // deleted, then written again with the published values: nothing left to publish
    recordService.deleteRecord(cp, "CP-2", "2026-Q3", "mistake", "dm@x");
    assertThat(recordService.countPendingChanges(cp)).isEqualTo(2);
    recordService.createRecord(
        cp, "CP-2", "2026-Q3", Map.of("NAME", "Beta AG"), Map.of(), "undo", "dm@x");
    assertThat(recordService.countPendingChanges(cp)).isEqualTo(1);
    assertThat(recordService.promoteToBeta(cp, "dm@x").deleted()).isZero();
    assertThat(recordService.findCurrentOne(cp, "CP-2", "2026-Q3", MicrodataStage.BETA))
        .isPresent();
  }
}
