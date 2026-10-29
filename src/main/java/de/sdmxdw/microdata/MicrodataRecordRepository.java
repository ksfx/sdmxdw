package de.sdmxdw.microdata;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface MicrodataRecordRepository extends ListCrudRepository<MicrodataRecord, Long> {

  /**
   * Filters push down into SQL via the "(:param IS NULL OR column = :param)" pattern instead of
   * loading every current record and filtering in Java - the same lesson Phase 6 learned the hard
   * way for the SDMX-REST key filter.
   */
  @Query(
      "SELECT * FROM microdata_record WHERE microdata_flow_id = :flowId AND stage = :stage"
          + " AND valid_to IS NULL"
          + " AND (:reportingPeriod IS NULL OR reporting_period = :reportingPeriod)"
          + " AND (:facet1 IS NULL OR facet1_value = :facet1)"
          + " AND (:facet2 IS NULL OR facet2_value = :facet2)"
          + " AND (:facet3 IS NULL OR facet3_value = :facet3)"
          + " AND (:facet4 IS NULL OR facet4_value = :facet4)"
          + " AND (:q IS NULL OR payload_json ILIKE '%' || :q || '%')"
          + " ORDER BY entity_id, reporting_period"
          + " LIMIT :limit OFFSET :offset")
  List<MicrodataRecord> findCurrentFiltered(
      @Param("flowId") Long flowId,
      @Param("stage") String stage,
      @Param("reportingPeriod") String reportingPeriod,
      @Param("facet1") String facet1,
      @Param("facet2") String facet2,
      @Param("facet3") String facet3,
      @Param("facet4") String facet4,
      @Param("q") String q,
      @Param("limit") int limit,
      @Param("offset") int offset);

  @Query(
      "SELECT COUNT(*) FROM microdata_record WHERE microdata_flow_id = :flowId AND stage = :stage"
          + " AND valid_to IS NULL"
          + " AND (:reportingPeriod IS NULL OR reporting_period = :reportingPeriod)"
          + " AND (:facet1 IS NULL OR facet1_value = :facet1)"
          + " AND (:facet2 IS NULL OR facet2_value = :facet2)"
          + " AND (:facet3 IS NULL OR facet3_value = :facet3)"
          + " AND (:facet4 IS NULL OR facet4_value = :facet4)"
          + " AND (:q IS NULL OR payload_json ILIKE '%' || :q || '%')")
  long countCurrentFiltered(
      @Param("flowId") Long flowId,
      @Param("stage") String stage,
      @Param("reportingPeriod") String reportingPeriod,
      @Param("facet1") String facet1,
      @Param("facet2") String facet2,
      @Param("facet3") String facet3,
      @Param("facet4") String facet4,
      @Param("q") String q);

  /** Feeds the reporting-period selector at the top of the microdata explorer. */
  @Query(
      "SELECT DISTINCT reporting_period FROM microdata_record WHERE stage = :stage"
          + " AND valid_to IS NULL ORDER BY reporting_period DESC")
  List<String> findDistinctReportingPeriods(@Param("stage") String stage);

  @Query(
      "SELECT * FROM microdata_record WHERE microdata_flow_id = :flowId AND stage = :stage"
          + " AND valid_to IS NULL ORDER BY entity_id, reporting_period")
  List<MicrodataRecord> findAllCurrent(@Param("flowId") Long flowId, @Param("stage") String stage);

  @Query(
      "SELECT * FROM microdata_record WHERE microdata_flow_id = :flowId AND entity_id = :entityId"
          + " AND reporting_period = :reportingPeriod AND stage = :stage ORDER BY valid_from")
  List<MicrodataRecord> findHistory(
      @Param("flowId") Long flowId,
      @Param("entityId") String entityId,
      @Param("reportingPeriod") String reportingPeriod,
      @Param("stage") String stage);

  @Query(
      "SELECT * FROM microdata_record WHERE microdata_flow_id = :flowId AND entity_id = :entityId"
          + " AND reporting_period = :reportingPeriod AND stage = :stage AND valid_to IS NULL")
  Optional<MicrodataRecord> findCurrentOne(
      @Param("flowId") Long flowId,
      @Param("entityId") String entityId,
      @Param("reportingPeriod") String reportingPeriod,
      @Param("stage") String stage);

  @Modifying
  @Query(
      "UPDATE microdata_record SET valid_to = :validTo WHERE microdata_flow_id = :flowId"
          + " AND entity_id = :entityId AND reporting_period = :reportingPeriod AND stage = :stage"
          + " AND valid_to IS NULL")
  void closeCurrentVintage(
      @Param("flowId") Long flowId,
      @Param("entityId") String entityId,
      @Param("reportingPeriod") String reportingPeriod,
      @Param("stage") String stage,
      @Param("validTo") Instant validTo);
}
