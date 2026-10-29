package de.sdmxdw.structure.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

class CategorySchemeServiceTest extends AbstractIntegrationTest {

  @Autowired private CategorySchemeService categorySchemeService;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void buildsCategoryHierarchy() {
    CategoryScheme scheme =
        categorySchemeService.createScheme("SDMXDW", "CS_NAV", "1.0.0", "Navigation");

    CategoryScheme withRoot =
        categorySchemeService.addCategory(scheme.id(), null, "ECO", "Economy");
    Long rootId = withRoot.categories().iterator().next().id();

    CategoryScheme withChild =
        categorySchemeService.addCategory(scheme.id(), rootId, "PRICES", "Prices");

    assertThat(withChild.categories()).hasSize(2);
    assertThat(withChild.categories().stream().anyMatch(c -> rootId.equals(c.parentCategoryId())))
        .isTrue();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void rejectsParentFromDifferentScheme() {
    CategoryScheme schemeA = categorySchemeService.createScheme("SDMXDW", "CS_A", "1.0.0", "A");
    CategoryScheme schemeB = categorySchemeService.createScheme("SDMXDW", "CS_B", "1.0.0", "B");
    CategoryScheme withCategory = categorySchemeService.addCategory(schemeA.id(), null, "X", "X");
    Long categoryInA = withCategory.categories().iterator().next().id();

    assertThatThrownBy(() -> categorySchemeService.addCategory(schemeB.id(), categoryInA, "Y", "Y"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void updatesDescriptionWithoutTouchingCategories() {
    CategoryScheme scheme = categorySchemeService.createScheme("SDMXDW", "CS_DESC", "1.0.0", "D");
    Long categoryId =
        categorySchemeService
            .addCategory(scheme.id(), null, "X", "X")
            .categories()
            .iterator()
            .next()
            .id();

    CategoryScheme updated = categorySchemeService.updateDescription(scheme.id(), "  # About\n ");

    assertThat(updated.description()).isEqualTo("# About");
    assertThat(updated.categories()).extracting(Category::id).containsExactly(categoryId);
    assertThat(categorySchemeService.updateDescription(scheme.id(), "   ").description()).isNull();
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomCannotEditDescription() {
    Long id = categorySchemeService.findAll().getFirst().id();
    assertThatThrownBy(() -> categorySchemeService.updateDescription(id, "x"))
        .isInstanceOf(AccessDeniedException.class);
  }
}
