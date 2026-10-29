package de.sdmxdw.process;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface ProcessRunRepository extends ListCrudRepository<ProcessRun, Long> {

  List<ProcessRun> findByProcessIdAndParentRunIdIsNullOrderByQueuedAtDesc(
      Long processId, Pageable pageable);

  long countByProcessIdAndParentRunIdIsNull(Long processId);

  @Query(
      "SELECT * FROM process_run WHERE process_id = :processId AND parent_run_id IS NULL"
          + " ORDER BY queued_at DESC LIMIT 1")
  Optional<ProcessRun> findLatest(@Param("processId") Long processId);

  @Query("SELECT * FROM process_run WHERE status IN ('QUEUED', 'RUNNING')")
  List<ProcessRun> findUnfinished();

  @Query(
      "SELECT * FROM process_run WHERE process_id = :processId AND status = 'QUEUED'"
          + " AND parent_run_id IS NULL ORDER BY queued_at LIMIT 1")
  Optional<ProcessRun> findNextQueued(@Param("processId") Long processId);

  List<ProcessRun> findByParentRunIdOrderByQueuedAt(Long parentRunId);

  @Modifying
  @Query("DELETE FROM process_run WHERE finished_at < :before AND parent_run_id IS NULL")
  int deleteFinishedBefore(@Param("before") Instant before);
}
