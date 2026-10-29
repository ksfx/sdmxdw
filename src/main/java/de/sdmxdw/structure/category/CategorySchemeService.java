package de.sdmxdw.structure.category;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategorySchemeService {

  private final CategorySchemeRepository categorySchemeRepository;

  public CategorySchemeService(CategorySchemeRepository categorySchemeRepository) {
    this.categorySchemeRepository = categorySchemeRepository;
  }

  public List<CategoryScheme> findAll() {
    return categorySchemeRepository.findAll();
  }

  public CategoryScheme findById(Long id) {
    return categorySchemeRepository.findById(id).orElseThrow();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public CategoryScheme createScheme(
      String agencyId, String artefactId, String version, String name) {
    return categorySchemeRepository.save(
        CategoryScheme.createNew(agencyId, artefactId, version, name));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public CategoryScheme updateDescription(Long schemeId, String markdown) {
    findById(schemeId);
    categorySchemeRepository.updateDescription(
        schemeId, markdown == null || markdown.isBlank() ? null : markdown.strip());
    return findById(schemeId);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public CategoryScheme addCategory(
      Long schemeId, Long parentCategoryId, String code, String name) {
    CategoryScheme scheme = findById(schemeId);
    if (parentCategoryId != null
        && scheme.categories().stream().noneMatch(c -> c.id().equals(parentCategoryId))) {
      throw new IllegalArgumentException(
          "Parent category " + parentCategoryId + " does not belong to scheme " + schemeId);
    }
    Set<Category> categories = new HashSet<>(scheme.categories());
    categories.add(new Category(null, parentCategoryId, code, name));
    return categorySchemeRepository.save(scheme.withCategories(categories));
  }
}
