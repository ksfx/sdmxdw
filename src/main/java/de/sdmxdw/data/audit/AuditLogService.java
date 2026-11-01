package de.sdmxdw.data.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;

  public AuditLogService(AuditLogRepository auditLogRepository) {
    this.auditLogRepository = auditLogRepository;
  }

  public AuditLogEntry record(Long dataflowId, String action, String performedBy, String details) {
    return auditLogRepository.save(
        AuditLogEntry.createNew(dataflowId, action, performedBy, Instant.now(), details));
  }

  public AuditLogEntry recordMicrodata(
      Long microdataFlowId, String action, String performedBy, String details) {
    return auditLogRepository.save(
        AuditLogEntry.createNewForMicrodata(
            microdataFlowId, action, performedBy, Instant.now(), details));
  }

  public AuditLogEntry recordProcess(
      Long processId, String action, String performedBy, String details) {
    return auditLogRepository.save(
        AuditLogEntry.createNewForProcess(processId, action, performedBy, Instant.now(), details));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public AuditPage findByProcess(Long processId, int page, int size) {
    int safePage = Math.max(page, 0);
    return new AuditPage(
        auditLogRepository.findByProcessIdOrderByPerformedAtDesc(
            processId, PageRequest.of(safePage, size)),
        auditLogRepository.countByProcessId(processId),
        safePage,
        size);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public AuditPage findByMicrodataFlow(Long microdataFlowId, int page, int size) {
    int safePage = Math.max(page, 0);
    return new AuditPage(
        auditLogRepository.findByMicrodataFlowIdOrderByPerformedAtDesc(
            microdataFlowId, PageRequest.of(safePage, size)),
        auditLogRepository.countByMicrodataFlowId(microdataFlowId),
        safePage,
        size);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<AuditLogEntry> findByDataflow(Long dataflowId) {
    return auditLogRepository.findByDataflowIdOrderByPerformedAtDesc(dataflowId);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public AuditPage findByDataflow(Long dataflowId, int page, int size) {
    int safePage = Math.max(page, 0);
    return new AuditPage(
        auditLogRepository.findByDataflowIdOrderByPerformedAtDesc(
            dataflowId, PageRequest.of(safePage, size)),
        auditLogRepository.countByDataflowId(dataflowId),
        safePage,
        size);
  }

  public record AuditPage(List<AuditLogEntry> entries, long total, int page, int size) {
    public int totalPages() {
      return (int) Math.ceil(total / (double) size);
    }
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<AuditLogEntry> findAll() {
    return auditLogRepository.findAllByOrderByPerformedAtDesc();
  }
}
