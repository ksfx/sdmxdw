package de.sdmxdw.structure.dataflow;

import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface DataflowRepository extends ListCrudRepository<Dataflow, Long> {

  Optional<Dataflow> findByAgencyIdAndArtefactIdAndVersion(
      String agencyId, String artefactId, String version);
}
