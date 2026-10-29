package de.sdmxdw.microdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

class MicrodataCategorisationServiceTest extends AbstractIntegrationTest {

  @Autowired private MicrodataCategorisationService microdataCategorisationService;

  @Autowired private MicrodataFlowService microdataFlowService;

  @Autowired private CategorySchemeService categorySchemeService;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void reassigningFlowReplacesPreviousCategorisation() {
    CategoryScheme scheme =
        categorySchemeService.createScheme("SDMXDW", "CS_MD_NAV", "1.0.0", "Nav");
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

    MicrodataFlow flow =
        microdataFlowService.createFlow("DEMO", "MDF_CAT", "1.0.0", "Cat flow", null);

    microdataCategorisationService.assignFlowToCategory(flow.id(), categoryA);
    microdataCategorisationService.assignFlowToCategory(flow.id(), categoryB);

    assertThat(microdataCategorisationService.findByCategoryId(categoryA)).isEmpty();
    assertThat(microdataCategorisationService.findByCategoryId(categoryB)).hasSize(1);
    assertThat(microdataCategorisationService.findByMicrodataFlowId(flow.id())).isPresent();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void rejectsUnknownFlowOrCategory() {
    CategoryScheme scheme =
        categorySchemeService.createScheme("SDMXDW", "CS_MD_NAV2", "1.0.0", "Nav2");
    CategoryScheme withCategory = categorySchemeService.addCategory(scheme.id(), null, "A", "A");
    Long categoryId = withCategory.categories().iterator().next().id();

    assertThatThrownBy(
            () -> microdataCategorisationService.assignFlowToCategory(999_999L, categoryId))
        .isInstanceOf(IllegalArgumentException.class);

    MicrodataFlow flow =
        microdataFlowService.createFlow("DEMO", "MDF_CAT2", "1.0.0", "Cat flow 2", null);
    assertThatThrownBy(
            () -> microdataCategorisationService.assignFlowToCategory(flow.id(), 999_999L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
