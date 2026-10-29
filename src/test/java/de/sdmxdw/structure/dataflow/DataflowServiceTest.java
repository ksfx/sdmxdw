package de.sdmxdw.structure.dataflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

class DataflowServiceTest extends AbstractIntegrationTest {

  @Autowired private DataflowService dataflowService;

  @Autowired private DataStructureDefinitionService dsdService;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void createsDataflowReferencingExistingDsd() {
    DataStructureDefinition dsd = dsdService.createDsd("SDMXDW", "DSD_DF", "1.0.0", "DF DSD");

    Dataflow dataflow =
        dataflowService.createDataflow("SDMXDW", "DF_ECO", "1.0.0", "Economic dataflow", dsd.id());

    assertThat(dataflow.id()).isNotNull();
    assertThat(dataflow.dsdId()).isEqualTo(dsd.id());
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void rejectsUnknownDsd() {
    assertThatThrownBy(
            () -> dataflowService.createDataflow("SDMXDW", "DF_X", "1.0.0", "X", 999_999L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
