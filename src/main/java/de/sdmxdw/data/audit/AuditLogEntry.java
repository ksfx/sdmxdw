package de.sdmxdw.data.audit;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("audit_log")
public record AuditLogEntry(
    @Id Long id,
    Long dataflowId,
    Long microdataFlowId,
    Long processId,
    String action,
    String performedBy,
    Instant performedAt,
    String details) {

  public static AuditLogEntry createNew(
      Long dataflowId, String action, String performedBy, Instant performedAt, String details) {
    return new AuditLogEntry(
        null, dataflowId, null, null, action, performedBy, performedAt, details);
  }

  public static AuditLogEntry createNewForMicrodata(
      Long microdataFlowId,
      String action,
      String performedBy,
      Instant performedAt,
      String details) {
    return new AuditLogEntry(
        null, null, microdataFlowId, null, action, performedBy, performedAt, details);
  }

  public static AuditLogEntry createNewForProcess(
      Long processId, String action, String performedBy, Instant performedAt, String details) {
    return new AuditLogEntry(
        null, null, null, processId, action, performedBy, performedAt, details);
  }
}
