package de.sdmxdw.structure.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import de.sdmxdw.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

class CodelistServiceTest extends AbstractIntegrationTest {

  @Autowired private CodelistService codelistService;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void createsCodelistAndAddsCodes() {
    Codelist codelist = codelistService.createCodelist("SDMXDW", "CL_FREQ", "1.0.0", "Frequency");

    Codelist updated = codelistService.addCode(codelist.id(), "A", "Annual");

    assertThat(updated.codes()).hasSize(1);
    assertThat(updated.codes().iterator().next().code()).isEqualTo("A");
  }
}
