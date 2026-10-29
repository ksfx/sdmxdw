package de.sdmxdw.structure.categorisation;

import de.sdmxdw.structure.category.CategorySchemeRepository;
import de.sdmxdw.structure.dataflow.DataflowRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * V1 keeps exactly one categorisation per dataflow (assigning a new one replaces the old one), even
 * though the table could technically hold several for a dataflow later on.
 */
@Service
public class CategorisationService {

  private final CategorisationRepository categorisationRepository;
  private final DataflowRepository dataflowRepository;
  private final CategorySchemeRepository categorySchemeRepository;

  public CategorisationService(
      CategorisationRepository categorisationRepository,
      DataflowRepository dataflowRepository,
      CategorySchemeRepository categorySchemeRepository) {
    this.categorisationRepository = categorisationRepository;
    this.dataflowRepository = dataflowRepository;
    this.categorySchemeRepository = categorySchemeRepository;
  }

  public List<Categorisation> findAll() {
    return categorisationRepository.findAll();
  }

  public List<Categorisation> findByCategoryId(Long categoryId) {
    return categorisationRepository.findByCategoryId(categoryId);
  }

  public Optional<Categorisation> findByDataflowId(Long dataflowId) {
    return categorisationRepository.findByDataflowId(dataflowId);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public Categorisation assignDataflowToCategory(Long dataflowId, Long categoryId) {
    if (dataflowRepository.findById(dataflowId).isEmpty()) {
      throw new IllegalArgumentException("Unknown dataflow id: " + dataflowId);
    }
    requireCategoryExists(categoryId);
    categorisationRepository
        .findByDataflowId(dataflowId)
        .ifPresent(categorisationRepository::delete);
    return categorisationRepository.save(Categorisation.createNew(dataflowId, categoryId));
  }

  private void requireCategoryExists(Long categoryId) {
    if (!categorySchemeRepository.existsCategoryById(categoryId)) {
      throw new IllegalArgumentException("Unknown category id: " + categoryId);
    }
  }
}
