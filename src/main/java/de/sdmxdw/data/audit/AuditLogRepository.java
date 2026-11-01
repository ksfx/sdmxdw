package de.sdmxdw.data.audit;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.ListCrudRepository;

public interface AuditLogRepository extends ListCrudRepository<AuditLogEntry, Long> {

  List<AuditLogEntry> findByDataflowIdOrderByPerformedAtDesc(Long dataflowId);

  List<AuditLogEntry> findByDataflowIdOrderByPerformedAtDesc(Long dataflowId, Pageable pageable);

  long countByDataflowId(Long dataflowId);

  List<AuditLogEntry> findByMicrodataFlowIdOrderByPerformedAtDesc(
      Long microdataFlowId, Pageable pageable);

  long countByMicrodataFlowId(Long microdataFlowId);

  List<AuditLogEntry> findByProcessIdOrderByPerformedAtDesc(Long processId, Pageable pageable);

  long countByProcessId(Long processId);

  List<AuditLogEntry> findAllByOrderByPerformedAtDesc();
}
