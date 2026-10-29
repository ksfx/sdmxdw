package de.sdmxdw.structure.codelist;

import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

@Table("codelist")
public record Codelist(
    @Id Long id,
    String agencyId,
    String artefactId,
    String version,
    String name,
    @MappedCollection(idColumn = "codelist_id") Set<Code> codes) {

  public static Codelist createNew(
      String agencyId, String artefactId, String version, String name) {
    return new Codelist(null, agencyId, artefactId, version, name, Set.of());
  }

  public Codelist withCodes(Set<Code> newCodes) {
    return new Codelist(id, agencyId, artefactId, version, name, newCodes);
  }
}
