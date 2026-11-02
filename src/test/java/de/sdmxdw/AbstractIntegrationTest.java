package de.sdmxdw;

import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Base of the integration tests. The environment variable SDMXDW_PROCESSES_DIR of an installation
 * binds to sdmxdw.processes.dir and would win over application.properties of the tests - a build on
 * a server would then create test folders in its real process folder. Inline properties win over
 * environment variables.
 */
@SpringBootTest(properties = AbstractIntegrationTest.TEST_PROCESSES_DIR)
public abstract class AbstractIntegrationTest {

  public static final String TEST_PROCESSES_DIR = "sdmxdw.processes.dir=target/test-processes";

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
