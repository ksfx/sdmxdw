package de.sdmxdw.structure.conceptscheme;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class ConceptSchemeService {

  private final ConceptSchemeRepository conceptSchemeRepository;

  public ConceptSchemeService(ConceptSchemeRepository conceptSchemeRepository) {
    this.conceptSchemeRepository = conceptSchemeRepository;
  }

  public List<ConceptScheme> findAll() {
    return conceptSchemeRepository.findAll();
  }

  public ConceptScheme findById(Long id) {
    return conceptSchemeRepository.findById(id).orElseThrow();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public ConceptScheme createScheme(
      String agencyId, String artefactId, String version, String name) {
    return conceptSchemeRepository.save(
        ConceptScheme.createNew(agencyId, artefactId, version, name));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public ConceptScheme addConcept(Long schemeId, String code, String name) {
    ConceptScheme scheme = findById(schemeId);
    Set<Concept> concepts = new HashSet<>(scheme.concepts());
    concepts.add(new Concept(null, code, name));
    return conceptSchemeRepository.save(scheme.withConcepts(concepts));
  }
}
