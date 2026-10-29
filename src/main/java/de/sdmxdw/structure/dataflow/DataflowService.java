package de.sdmxdw.structure.dataflow;

import de.sdmxdw.structure.dsd.DataStructureDefinitionRepository;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class DataflowService {

  private final DataflowRepository dataflowRepository;
  private final DataStructureDefinitionRepository dsdRepository;

  public DataflowService(
      DataflowRepository dataflowRepository, DataStructureDefinitionRepository dsdRepository) {
    this.dataflowRepository = dataflowRepository;
    this.dsdRepository = dsdRepository;
  }

  public List<Dataflow> findAll() {
    return dataflowRepository.findAll();
  }

  public Dataflow findById(Long id) {
    return dataflowRepository.findById(id).orElseThrow();
  }

  public Dataflow findByTriple(String agencyId, String artefactId, String version) {
    return dataflowRepository
        .findByAgencyIdAndArtefactIdAndVersion(agencyId, artefactId, version)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Unknown dataflow: " + agencyId + "," + artefactId + "," + version));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public Dataflow createDataflow(
      String agencyId, String artefactId, String version, String name, Long dsdId) {
    if (dsdRepository.findById(dsdId).isEmpty()) {
      throw new IllegalArgumentException("Unknown DSD id: " + dsdId);
    }
    return dataflowRepository.save(Dataflow.createNew(agencyId, artefactId, version, name, dsdId));
  }
}
