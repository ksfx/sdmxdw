package de.sdmxdw.microdata;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("microdata_record")
public record MicrodataRecord(
    @Id Long id,
    Long microdataFlowId,
    String entityId,
    String reportingPeriod,
    MicrodataStage stage,
    String facet1Value,
    String facet2Value,
    String facet3Value,
    String facet4Value,
    String payloadJson,
    Instant validFrom,
    Instant validTo,
    String recordedBy,
    String comment) {

  public static MicrodataRecord createNew(
      Long microdataFlowId,
      String entityId,
      String reportingPeriod,
      MicrodataStage stage,
      String facet1Value,
      String facet2Value,
      String facet3Value,
      String facet4Value,
      String payloadJson,
      Instant validFrom,
      String recordedBy) {
    return createNew(
        microdataFlowId,
        entityId,
        reportingPeriod,
        stage,
        facet1Value,
        facet2Value,
        facet3Value,
        facet4Value,
        payloadJson,
        validFrom,
        recordedBy,
        null);
  }

  /**
   * @param comment reason of a manual correction (null for regular deliveries)
   */
  public static MicrodataRecord createNew(
      Long microdataFlowId,
      String entityId,
      String reportingPeriod,
      MicrodataStage stage,
      String facet1Value,
      String facet2Value,
      String facet3Value,
      String facet4Value,
      String payloadJson,
      Instant validFrom,
      String recordedBy,
      String comment) {
    return new MicrodataRecord(
        null,
        microdataFlowId,
        entityId,
        reportingPeriod,
        stage,
        facet1Value,
        facet2Value,
        facet3Value,
        facet4Value,
        payloadJson,
        validFrom,
        null,
        recordedBy,
        comment);
  }
}
