package de.sdmxdw;

import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public abstract class AbstractIntegrationTest {

  private static final AtomicBoolean DATABASE_RESET = new AtomicBoolean(false);

  @BeforeAll
  static void resetDatabaseOnce() {
    if (DATABASE_RESET.compareAndSet(false, true)) {
      Flyway.configure()
          .dataSource("jdbc:postgresql://localhost:5432/sdmxdw_test", "sdmxdw", "sdmxdw")
          .cleanDisabled(false)
          .load()
          .clean();
    }
  }
}
