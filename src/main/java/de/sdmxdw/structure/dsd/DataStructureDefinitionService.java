package de.sdmxdw.structure.dsd;

import de.sdmxdw.structure.codelist.CodelistRepository;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class DataStructureDefinitionService {

  private final DataStructureDefinitionRepository dsdRepository;
  private final ConceptSchemeRepository conceptSchemeRepository;
  private final CodelistRepository codelistRepository;

  public DataStructureDefinitionService(
      DataStructureDefinitionRepository dsdRepository,
      ConceptSchemeRepository conceptSchemeRepository,
      CodelistRepository codelistRepository) {
    this.dsdRepository = dsdRepository;
    this.conceptSchemeRepository = conceptSchemeRepository;
    this.codelistRepository = codelistRepository;
  }

  public List<DataStructureDefinition> findAll() {
    return dsdRepository.findAll();
  }

  public DataStructureDefinition findById(Long id) {
    return dsdRepository.findById(id).orElseThrow();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public DataStructureDefinition createDsd(
      String agencyId, String artefactId, String version, String name) {
    return dsdRepository.save(
        DataStructureDefinition.createNew(agencyId, artefactId, version, name));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public DataStructureDefinition addDimension(
      Long dsdId, Long conceptId, Long codelistId, DataType dataType) {
    requireConceptExists(conceptId);
    if (codelistId != null) {
      requireCodelistExists(codelistId);
    }
    DataStructureDefinition dsd = findById(dsdId);
    int nextPosition = dsd.dimensions().stream().mapToInt(Dimension::position).max().orElse(0) + 1;
    Set<Dimension> dimensions = new HashSet<>(dsd.dimensions());
    dimensions.add(new Dimension(null, nextPosition, conceptId, codelistId, dataType));
    return dsdRepository.save(dsd.withDimensions(dimensions));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public DataStructureDefinition addMeasure(Long dsdId, Long conceptId, DataType dataType) {
    requireConceptExists(conceptId);
    DataStructureDefinition dsd = findById(dsdId);
    Set<Measure> measures = new HashSet<>(dsd.measures());
    measures.add(new Measure(null, conceptId, dataType));
    return dsdRepository.save(dsd.withMeasures(measures));
  }

  private void requireConceptExists(Long conceptId) {
    if (!conceptSchemeRepository.existsConceptById(conceptId)) {
      throw new IllegalArgumentException("Unknown concept id: " + conceptId);
    }
  }

  private void requireCodelistExists(Long codelistId) {
    if (codelistRepository.findById(codelistId).isEmpty()) {
      throw new IllegalArgumentException("Unknown codelist id: " + codelistId);
    }
  }
}
