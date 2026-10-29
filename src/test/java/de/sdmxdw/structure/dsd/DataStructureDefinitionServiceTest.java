package de.sdmxdw.structure.dsd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

class DataStructureDefinitionServiceTest extends AbstractIntegrationTest {

  @Autowired private DataStructureDefinitionService dsdService;

  @Autowired private ConceptSchemeService conceptSchemeService;

  @Autowired private CodelistService codelistService;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void buildsDsdWithCodedDimensionTimeDimensionAndMeasure() {
    ConceptScheme concepts =
        conceptSchemeService.createScheme("SDMXDW", "CS_ECO_DSD", "1.0.0", "Economic concepts");
    concepts = conceptSchemeService.addConcept(concepts.id(), "FREQ", "Frequency");
    concepts = conceptSchemeService.addConcept(concepts.id(), "TIME_PERIOD", "Time period");
    concepts = conceptSchemeService.addConcept(concepts.id(), "OBS_VALUE", "Observation value");

    Long freqConceptId = conceptIdByCode(concepts, "FREQ");
    Long timeConceptId = conceptIdByCode(concepts, "TIME_PERIOD");
    Long obsValueConceptId = conceptIdByCode(concepts, "OBS_VALUE");

    Codelist freqCodelist =
        codelistService.createCodelist("SDMXDW", "CL_FREQ_DSD", "1.0.0", "Frequency");

    DataStructureDefinition dsd =
        dsdService.createDsd("SDMXDW", "DSD_ECO", "1.0.0", "Economic DSD");
    dsd = dsdService.addDimension(dsd.id(), freqConceptId, freqCodelist.id(), DataType.STRING);
    dsd = dsdService.addDimension(dsd.id(), timeConceptId, null, DataType.DATE);
    dsd = dsdService.addMeasure(dsd.id(), obsValueConceptId, DataType.NUMBER);

    assertThat(dsd.dimensions()).hasSize(2);
    assertThat(dsd.measures()).hasSize(1);
    assertThat(dsd.dimensions().stream().map(Dimension::position)).containsExactlyInAnyOrder(1, 2);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void rejectsDimensionWithUnknownConcept() {
    DataStructureDefinition dsd = dsdService.createDsd("SDMXDW", "DSD_X", "1.0.0", "X");

    assertThatThrownBy(() -> dsdService.addDimension(dsd.id(), 999_999L, null, DataType.STRING))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Long conceptIdByCode(ConceptScheme scheme, String code) {
    return scheme.concepts().stream()
        .filter(c -> c.code().equals(code))
        .findFirst()
        .orElseThrow()
        .id();
  }
}
