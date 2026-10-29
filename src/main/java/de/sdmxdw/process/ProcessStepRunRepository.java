package de.sdmxdw.process;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface ProcessStepRunRepository extends ListCrudRepository<ProcessStepRun, Long> {

  List<ProcessStepRun> findByRunIdOrderByPosition(Long runId);

  /** Live log: written while the step is running, without touching the other columns. */
  @Modifying
  @Query("UPDATE process_step_run SET log = :log WHERE id = :id")
  void updateLog(@Param("id") Long id, @Param("log") String log);
}
