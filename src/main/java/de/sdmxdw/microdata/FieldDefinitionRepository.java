package de.sdmxdw.microdata;

import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface FieldDefinitionRepository extends ListCrudRepository<FieldDefinition, Long> {

  @Query(
      "SELECT * FROM microdata_field_definition WHERE microdata_flow_id = :microdataFlowId"
          + " ORDER BY position")
  List<FieldDefinition> findByMicrodataFlowIdOrderByPosition(
      @Param("microdataFlowId") Long microdataFlowId);
}
