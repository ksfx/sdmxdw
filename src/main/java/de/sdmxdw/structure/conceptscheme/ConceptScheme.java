package de.sdmxdw.structure.conceptscheme;

import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

@Table("concept_scheme")
public record ConceptScheme(
    @Id Long id,
    String agencyId,
    String artefactId,
    String version,
    String name,
    @MappedCollection(idColumn = "concept_scheme_id") Set<Concept> concepts) {

  public static ConceptScheme createNew(
      String agencyId, String artefactId, String version, String name) {
    return new ConceptScheme(null, agencyId, artefactId, version, name, Set.of());
  }

  public ConceptScheme withConcepts(Set<Concept> newConcepts) {
    return new ConceptScheme(id, agencyId, artefactId, version, name, newConcepts);
  }
}
