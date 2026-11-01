package de.sdmxdw.data.observation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface ObservationRepository extends ListCrudRepository<Observation, Long> {

  @Query(
      "SELECT * FROM observation WHERE dataflow_id = :dataflowId AND stage = :stage"
          + " AND valid_to IS NULL ORDER BY series_id, time_period")
  List<Observation> findCurrent(@Param("dataflowId") Long dataflowId, @Param("stage") String stage);

  @Query(
      "SELECT * FROM observation WHERE dataflow_id = :dataflowId AND stage = :stage"
          + " AND valid_from <= :asOf AND (valid_to IS NULL OR valid_to > :asOf)"
          + " ORDER BY series_id, time_period")
  List<Observation> findAsOf(
      @Param("dataflowId") Long dataflowId,
      @Param("stage") String stage,
      @Param("asOf") Instant asOf);

  @Query(
      "SELECT * FROM observation WHERE dataflow_id = :dataflowId AND stage = :stage"
          + " AND series_id IN (:seriesIds) AND valid_to IS NULL ORDER BY series_id, time_period")
  List<Observation> findCurrentForSeriesIds(
      @Param("dataflowId") Long dataflowId,
      @Param("stage") String stage,
      @Param("seriesIds") Collection<Long> seriesIds);

  @Query(
      "SELECT * FROM observation WHERE dataflow_id = :dataflowId AND stage = :stage"
          + " AND series_id IN (:seriesIds) AND valid_from <= :asOf"
          + " AND (valid_to IS NULL OR valid_to > :asOf) ORDER BY series_id, time_period")
  List<Observation> findAsOfForSeriesIds(
      @Param("dataflowId") Long dataflowId,
      @Param("stage") String stage,
      @Param("seriesIds") Collection<Long> seriesIds,
      @Param("asOf") Instant asOf);

  @Query(
      "SELECT * FROM observation WHERE dataflow_id = :dataflowId AND series_id = :seriesId"
          + " AND time_period = :timePeriod AND stage = :stage AND valid_to IS NULL")
  Optional<Observation> findCurrentOne(
      @Param("dataflowId") Long dataflowId,
      @Param("seriesId") Long seriesId,
      @Param("timePeriod") String timePeriod,
      @Param("stage") String stage);

  @Query(
      "SELECT * FROM observation WHERE series_id = :seriesId AND time_period = :timePeriod"
          + " AND stage = :stage ORDER BY valid_from")
  List<Observation> findHistory(
      @Param("seriesId") Long seriesId,
      @Param("timePeriod") String timePeriod,
      @Param("stage") String stage);

  @Query(
      "SELECT * FROM observation WHERE series_id = :seriesId AND stage = :stage"
          + " ORDER BY time_period, valid_from")
  List<Observation> findSeriesHistory(
      @Param("seriesId") Long seriesId, @Param("stage") String stage);

  @Modifying
  @Query(
      "UPDATE observation SET valid_to = :validTo WHERE dataflow_id = :dataflowId"
          + " AND series_id = :seriesId AND time_period = :timePeriod AND stage = :stage"
          + " AND valid_to IS NULL")
  void closeCurrentVintage(
      @Param("dataflowId") Long dataflowId,
      @Param("seriesId") Long seriesId,
      @Param("timePeriod") String timePeriod,
      @Param("stage") String stage,
      @Param("validTo") Instant validTo);
}
