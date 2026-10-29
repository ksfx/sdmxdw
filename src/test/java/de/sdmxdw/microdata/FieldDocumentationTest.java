package de.sdmxdw.microdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

class FieldDocumentationTest extends AbstractIntegrationTest {

  @Autowired private MicrodataFlowService microdataFlowService;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void storesObligationAndSubtypeOfAField() {
    MicrodataFlow flow =
        microdataFlowService.createFlow("DEMO", "MDF_DOC_A", "1.0.0", "Doc test", null);
    FieldDefinition field =
        microdataFlowService.addField(
            flow.id(), "UID", "UID", FieldDataType.STRING, null, false, null);

    microdataFlowService.documentField(field.id(), "M", " Firmenkunde Schweiz ", "REG-0024");

    FieldDefinition reloaded = microdataFlowService.findFields(flow.id()).get(0);
    assertThat(reloaded.obligation()).isEqualTo("M");
    assertThat(reloaded.subtype()).isEqualTo("Firmenkunde Schweiz");
    assertThat(reloaded.subtypeRule()).isEqualTo("REG-0024");
    assertThat(reloaded.required()).isFalse();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void rejectsUnknownObligation() {
    MicrodataFlow flow =
        microdataFlowService.createFlow("DEMO", "MDF_DOC_B", "1.0.0", "Doc test B", null);
    FieldDefinition field =
        microdataFlowService.addField(flow.id(), "X", "X", FieldDataType.STRING, null, false, null);

    assertThatThrownBy(
            () -> microdataFlowService.documentField(field.id(), "MANDATORY", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomCannotDocumentFields() {
    assertThatThrownBy(() -> microdataFlowService.documentField(1L, "M", null, null))
        .isInstanceOf(AccessDeniedException.class);
  }
}
