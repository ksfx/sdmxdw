package de.sdmxdw.microdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

class MicrodataFlowServiceTest extends AbstractIntegrationTest {

  @Autowired private MicrodataFlowService microdataFlowService;

  @Autowired private CodelistService codelistService;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void createsFlowAndAddsFieldsInOrderWithFacetSlots() {
    MicrodataFlow flow =
        microdataFlowService.createFlow(
            "DEMO", "MDF_FLOWTEST", "1.0.0", "Flow test", "description");

    Codelist industryCodelist =
        codelistService.createCodelist("DEMO", "CL_FLOWTEST_INDUSTRY", "1.0.0", "Industry");
    codelistService.addCode(industryCodelist.id(), "SERVICES", "Services");

    microdataFlowService.addField(
        flow.id(),
        "INDUSTRY",
        "Branche",
        FieldDataType.STRING,
        industryCodelist.id(),
        true,
        FacetSlot.facet1);
    microdataFlowService.addField(
        flow.id(), "AMOUNT", "Betrag", FieldDataType.NUMBER, null, true, null);

    List<FieldDefinition> fields = microdataFlowService.findFields(flow.id());
    assertThat(fields).hasSize(2);
    assertThat(fields.get(0).fieldCode()).isEqualTo("INDUSTRY");
    assertThat(fields.get(0).position()).isZero();
    assertThat(fields.get(0).facetSlot()).isEqualTo(FacetSlot.facet1);
    assertThat(fields.get(1).fieldCode()).isEqualTo("AMOUNT");
    assertThat(fields.get(1).position()).isEqualTo(1);
    assertThat(fields.get(1).facetSlot()).isNull();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void rejectsUnknownCodelistWhenAddingField() {
    MicrodataFlow flow =
        microdataFlowService.createFlow("DEMO", "MDF_FLOWTEST2", "1.0.0", "Flow test 2", null);

    assertThatThrownBy(
            () ->
                microdataFlowService.addField(
                    flow.id(), "INDUSTRY", "Branche", FieldDataType.STRING, 999_999L, true, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomCannotCreateFlowOrAddFields() {
    assertThatThrownBy(
            () -> microdataFlowService.createFlow("DEMO", "MDF_DENIED", "1.0.0", "x", null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                microdataFlowService.addField(
                    1L, "X", "X", FieldDataType.STRING, null, false, null))
        .isInstanceOf(AccessDeniedException.class);
  }
}
