package de.sdmxdw.microdata;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Filter for reading microdata records through the REST API.
 *
 * @param reportingPeriods empty = all periods
 * @param asOf null = current vintage, otherwise the vintage valid at that instant
 * @param fieldFilters scalar field code -> accepted values (facet fields are filtered on their
 *     indexed column, other fields on the JSON payload)
 * @param freeText case-insensitive substring of entity id or payload (null = none)
 * @param limit null = no limit
 */
public record MicrodataQuery(
    Long flowId,
    MicrodataStage stage,
    List<String> reportingPeriods,
    Instant asOf,
    Map<String, List<String>> fieldFilters,
    String freeText,
    Integer limit,
    int offset) {

  public MicrodataQuery(
      Long flowId,
      MicrodataStage stage,
      List<String> reportingPeriods,
      Instant asOf,
      Map<String, List<String>> fieldFilters,
      Integer limit,
      int offset) {
    this(flowId, stage, reportingPeriods, asOf, fieldFilters, null, limit, offset);
  }

  public MicrodataQuery withStage(MicrodataStage newStage) {
    return new MicrodataQuery(
        flowId, newStage, reportingPeriods, asOf, fieldFilters, freeText, limit, offset);
  }
}
