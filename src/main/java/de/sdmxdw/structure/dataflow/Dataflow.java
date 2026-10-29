package de.sdmxdw.structure.dataflow;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("dataflow")
public record Dataflow(
    @Id Long id, String agencyId, String artefactId, String version, String name, Long dsdId) {

  public static Dataflow createNew(
      String agencyId, String artefactId, String version, String name, Long dsdId) {
    return new Dataflow(null, agencyId, artefactId, version, name, dsdId);
  }
}
