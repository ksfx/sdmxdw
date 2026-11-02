package de.sdmxdw.microdata;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Microdata records live in one partition per flow and reporting period (V19, #17). Before records
 * of a new period are written, its partition has to exist. Creating a partition briefly locks the
 * whole table, so it runs in a short transaction of its own - never inside a long load, which would
 * otherwise block the explorer for minutes. Call {@link #ensure} before the writing transaction
 * touches {@code microdata_record}, or the new transaction would wait for it.
 */
@Component
public class MicrodataPartitions {

  /** One pair of flow and reporting period. */
  public record Key(Long flowId, String reportingPeriod) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate separate;
  private final Set<Key> known = ConcurrentHashMap.newKeySet();

  public MicrodataPartitions(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.separate = new TransactionTemplate(transactionManager);
    this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public void ensure(Long flowId, String reportingPeriod) {
    ensure(Set.of(new Key(flowId, reportingPeriod == null ? "" : reportingPeriod)));
  }

  public void ensure(Collection<Key> keys) {
    var missing = keys.stream().filter(k -> !known.contains(k)).toList();
    if (missing.isEmpty()) {
      return;
    }
    separate.executeWithoutResult(
        status -> {
          // fail instead of waiting forever if the caller already holds a lock on the table
          jdbc.execute("SET LOCAL lock_timeout = '30s'");
          for (Key k : missing) {
            jdbc.queryForList(
                "SELECT microdata_ensure_partition(?, ?)", k.flowId(), k.reportingPeriod());
          }
        });
    known.addAll(missing);
  }
}
