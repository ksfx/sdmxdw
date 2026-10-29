package de.sdmxdw.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

class ProcessSecretServiceTest extends AbstractIntegrationTest {

  @Autowired private ProcessSecretService secrets;
  @Autowired private ProcessSecretRepository repository;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void valuesAreEncryptedAndOnlyResolvedForRuns() {
    secrets.set("TEST_API_KEY", "s3cr3t-value", "dm@x");
    String stored = repository.findByName("TEST_API_KEY").orElseThrow().valueEncrypted();
    assertThat(stored).doesNotContain("s3cr3t");
    assertThat(secrets.resolve(List.of("TEST_API_KEY", "MISSING")))
        .containsExactly(java.util.Map.entry("TEST_API_KEY", "s3cr3t-value"));
    // replacing keeps one entry with a fresh ciphertext (random IV)
    secrets.set("TEST_API_KEY", "s3cr3t-value", "dm@x");
    assertThat(repository.findByName("TEST_API_KEY").orElseThrow().valueEncrypted())
        .isNotEqualTo(stored);

    assertThatThrownBy(() -> secrets.set("lower", "x", "dm"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> secrets.set("SDMXDW_X", "x", "dm"))
        .isInstanceOf(IllegalArgumentException.class);
    secrets.delete("TEST_API_KEY");
    assertThat(repository.findByName("TEST_API_KEY")).isEmpty();
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void economistsCannotManageSecrets() {
    assertThatThrownBy(() -> secrets.findAll()).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> secrets.set("X", "y", "oe")).isInstanceOf(AccessDeniedException.class);
  }
}
