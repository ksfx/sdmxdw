package de.sdmxdw.structure.conceptscheme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

class ConceptSchemeServiceTest extends AbstractIntegrationTest {

  @Autowired private ConceptSchemeService conceptSchemeService;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void createsSchemeAndAddsConcepts() {
    ConceptScheme scheme =
        conceptSchemeService.createScheme("SDMXDW", "CS_ECO", "1.0.0", "Economic concepts");

    ConceptScheme updated = conceptSchemeService.addConcept(scheme.id(), "FREQ", "Frequency");

    assertThat(updated.concepts()).hasSize(1);
    assertThat(updated.concepts().iterator().next().code()).isEqualTo("FREQ");
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomCannotCreateScheme() {
    assertThatThrownBy(() -> conceptSchemeService.createScheme("SDMXDW", "CS_X", "1.0.0", "X"))
        .isInstanceOf(AccessDeniedException.class);
  }
}
