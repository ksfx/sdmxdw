package de.sdmxdw.structure.category;

import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

@Table("category_scheme")
public record CategoryScheme(
    @Id Long id,
    String agencyId,
    String artefactId,
    String version,
    String name,
    String description,
    @MappedCollection(idColumn = "category_scheme_id") Set<Category> categories) {

  public static CategoryScheme createNew(
      String agencyId, String artefactId, String version, String name) {
    return new CategoryScheme(null, agencyId, artefactId, version, name, null, Set.of());
  }

  public CategoryScheme withCategories(Set<Category> newCategories) {
    return new CategoryScheme(id, agencyId, artefactId, version, name, description, newCategories);
  }
}
