package de.sdmxdw.structure.dsd;

import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

@Table("data_structure_definition")
public record DataStructureDefinition(
    @Id Long id,
    String agencyId,
    String artefactId,
    String version,
    String name,
    @MappedCollection(idColumn = "dsd_id") Set<Dimension> dimensions,
    @MappedCollection(idColumn = "dsd_id") Set<Measure> measures) {

  public static DataStructureDefinition createNew(
      String agencyId, String artefactId, String version, String name) {
    return new DataStructureDefinition(
        null, agencyId, artefactId, version, name, Set.of(), Set.of());
  }

  public DataStructureDefinition withDimensions(Set<Dimension> newDimensions) {
    return new DataStructureDefinition(
        id, agencyId, artefactId, version, name, newDimensions, measures);
  }

  public DataStructureDefinition withMeasures(Set<Measure> newMeasures) {
    return new DataStructureDefinition(
        id, agencyId, artefactId, version, name, dimensions, newMeasures);
  }
}
