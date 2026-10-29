package de.sdmxdw.microdata;

import de.sdmxdw.structure.category.CategorySchemeRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Mirrors {@code CategorisationService} for microdata flows: keeps exactly one categorisation per
 * flow (assigning a new one replaces the old one), so different microdata collections (surveys) can
 * be grouped in the same category tree the SDMX explorer already uses.
 */
@Service
public class MicrodataCategorisationService {

  private final MicrodataCategorisationRepository microdataCategorisationRepository;
  private final MicrodataFlowRepository microdataFlowRepository;
  private final CategorySchemeRepository categorySchemeRepository;

  public MicrodataCategorisationService(
      MicrodataCategorisationRepository microdataCategorisationRepository,
      MicrodataFlowRepository microdataFlowRepository,
      CategorySchemeRepository categorySchemeRepository) {
    this.microdataCategorisationRepository = microdataCategorisationRepository;
    this.microdataFlowRepository = microdataFlowRepository;
    this.categorySchemeRepository = categorySchemeRepository;
  }

  public List<MicrodataCategorisation> findByCategoryId(Long categoryId) {
    return microdataCategorisationRepository.findByCategoryId(categoryId);
  }

  public Optional<MicrodataCategorisation> findByMicrodataFlowId(Long microdataFlowId) {
    return microdataCategorisationRepository.findByMicrodataFlowId(microdataFlowId);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public MicrodataCategorisation assignFlowToCategory(Long microdataFlowId, Long categoryId) {
    if (microdataFlowRepository.findById(microdataFlowId).isEmpty()) {
      throw new IllegalArgumentException("Unknown microdata flow id: " + microdataFlowId);
    }
    if (!categorySchemeRepository.existsCategoryById(categoryId)) {
      throw new IllegalArgumentException("Unknown category id: " + categoryId);
    }
    microdataCategorisationRepository
        .findByMicrodataFlowId(microdataFlowId)
        .ifPresent(microdataCategorisationRepository::delete);
    return microdataCategorisationRepository.save(
        MicrodataCategorisation.createNew(microdataFlowId, categoryId));
  }
}
