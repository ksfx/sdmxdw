package de.sdmxdw.structure.categorisation;

import static org.assertj.core.api.Assertions.assertThat;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

class CategorisationServiceTest extends AbstractIntegrationTest {

  @Autowired private CategorisationService categorisationService;

  @Autowired private CategorySchemeService categorySchemeService;

  @Autowired private DataflowService dataflowService;

  @Autowired private DataStructureDefinitionService dsdService;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void reassigningDataflowReplacesPreviousCategorisation() {
    CategoryScheme scheme = categorySchemeService.createScheme("SDMXDW", "CS_NAV2", "1.0.0", "Nav");
    CategoryScheme withCategories = categorySchemeService.addCategory(scheme.id(), null, "A", "A");
    withCategories = categorySchemeService.addCategory(scheme.id(), null, "B", "B");
    Long categoryA =
        withCategories.categories().stream()
            .filter(c -> c.code().equals("A"))
            .findFirst()
            .orElseThrow()
            .id();
    Long categoryB =
        withCategories.categories().stream()
            .filter(c -> c.code().equals("B"))
            .findFirst()
            .orElseThrow()
            .id();

    DataStructureDefinition dsd = dsdService.createDsd("SDMXDW", "DSD_CAT", "1.0.0", "Cat DSD");
    Dataflow dataflow =
        dataflowService.createDataflow("SDMXDW", "DF_CAT", "1.0.0", "Cat DF", dsd.id());

    categorisationService.assignDataflowToCategory(dataflow.id(), categoryA);
    categorisationService.assignDataflowToCategory(dataflow.id(), categoryB);

    assertThat(categorisationService.findByCategoryId(categoryA)).isEmpty();
    assertThat(categorisationService.findByCategoryId(categoryB)).hasSize(1);
  }
}
