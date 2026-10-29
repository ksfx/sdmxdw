package de.sdmxdw.structure.conceptscheme;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface ConceptSchemeRepository extends ListCrudRepository<ConceptScheme, Long> {

  @Query("select exists(select 1 from concept where id = :conceptId)")
  boolean existsConceptById(@Param("conceptId") Long conceptId);
}
