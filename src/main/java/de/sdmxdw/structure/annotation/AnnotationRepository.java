package de.sdmxdw.structure.annotation;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface AnnotationRepository extends ListCrudRepository<Annotation, Long> {

  @Query(
      "SELECT * FROM annotation WHERE target_type = :type AND target_id = :id"
          + " ORDER BY position, id")
  List<Annotation> findByTarget(@Param("type") String type, @Param("id") Long id);

  @Query(
      "SELECT * FROM annotation WHERE target_type = :type AND target_id IN (:ids)"
          + " ORDER BY position, id")
  List<Annotation> findByTargets(@Param("type") String type, @Param("ids") Collection<Long> ids);

  @Query("SELECT * FROM annotation ORDER BY target_type, target_id, position, id")
  List<Annotation> findAllOrdered();
}
