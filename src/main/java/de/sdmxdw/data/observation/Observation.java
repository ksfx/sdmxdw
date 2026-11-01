package de.sdmxdw.data.observation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("observation")
public record Observation(
    @Id Long id,
    Long dataflowId,
    Long seriesId,
    String timePeriod,
    ObservationStage stage,
    String measureValuesJson,
    Instant validFrom,
    Instant validTo,
    String recordedBy,
    String comment) {

  public static Observation createNew(
      Long dataflowId,
      Long seriesId,
      String timePeriod,
      ObservationStage stage,
      String measureValuesJson,
      Instant validFrom,
      String recordedBy) {
    return createNew(
        dataflowId, seriesId, timePeriod, stage, measureValuesJson, validFrom, recordedBy, null);
  }

  public static Observation createNew(
      Long dataflowId,
      Long seriesId,
      String timePeriod,
      ObservationStage stage,
      String measureValuesJson,
      Instant validFrom,
      String recordedBy,
      String comment) {
    return new Observation(
        null,
        dataflowId,
        seriesId,
        timePeriod,
        stage,
        measureValuesJson,
        validFrom,
        null,
        recordedBy,
        comment);
  }

  public Observation withValidTo(Instant newValidTo) {
    return new Observation(
        id,
        dataflowId,
        seriesId,
        timePeriod,
        stage,
        measureValuesJson,
        validFrom,
        newValidTo,
        recordedBy,
        comment);
  }
}
