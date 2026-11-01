package de.sdmxdw.data.observation;

import de.sdmxdw.data.audit.AuditLogService;
import de.sdmxdw.data.series.Series;
import de.sdmxdw.data.series.SeriesRepository;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
import de.sdmxdw.structure.dsd.Dimension;
import de.sdmxdw.structure.dsd.Measure;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ObservationService {

  private final ObservationRepository observationRepository;
  private final SeriesRepository seriesRepository;
  private final DataflowService dataflowService;
  private final DataStructureDefinitionService dsdService;
  private final CodelistService codelistService;
  private final ConceptSchemeService conceptSchemeService;
  private final AuditLogService auditLogService;
  private final ObjectMapper objectMapper;
  private final ObservationQueryRepository observationQueryRepository;

  public ObservationService(
      ObservationRepository observationRepository,
      SeriesRepository seriesRepository,
      DataflowService dataflowService,
      DataStructureDefinitionService dsdService,
      CodelistService codelistService,
      ConceptSchemeService conceptSchemeService,
      AuditLogService auditLogService,
      ObjectMapper objectMapper,
      ObservationQueryRepository observationQueryRepository) {
    this.observationQueryRepository = observationQueryRepository;
    this.observationRepository = observationRepository;
    this.seriesRepository = seriesRepository;
    this.dataflowService = dataflowService;
    this.dsdService = dsdService;
    this.codelistService = codelistService;
    this.conceptSchemeService = conceptSchemeService;
    this.auditLogService = auditLogService;
    this.objectMapper = objectMapper;
  }

  /**
   * Ingest entry point for import plugins, which only know SDMX concept codes (e.g. "FREQ"), not
   * our internal surrogate dimension/measure ids.
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public Observation recordObservationByConceptCode(
      Long dataflowId,
      Map<String, String> dimensionValuesByConceptCode,
      Map<String, String> measureValuesByConceptCode,
      String recordedBy) {
    Dataflow dataflow = dataflowService.findById(dataflowId);
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    Map<Long, String> conceptCodeByConceptId = conceptCodeByConceptId();

    Map<Long, String> dimensionValuesByDimensionId = new LinkedHashMap<>();
    for (Dimension dimension : dsd.dimensions()) {
      String conceptCode = conceptCodeByConceptId.get(dimension.conceptId());
      String value = dimensionValuesByConceptCode.get(conceptCode);
      if (value != null) {
        dimensionValuesByDimensionId.put(dimension.id(), value);
      }
    }
    Map<Long, String> measureValuesByMeasureId = new LinkedHashMap<>();
    for (Measure measure : dsd.measures()) {
      String conceptCode = conceptCodeByConceptId.get(measure.conceptId());
      String value = measureValuesByConceptCode.get(conceptCode);
      if (value != null) {
        measureValuesByMeasureId.put(measure.id(), value);
      }
    }
    return recordObservation(
        dataflowId, dimensionValuesByDimensionId, measureValuesByMeasureId, recordedBy);
  }

  private Map<Long, String> conceptCodeByConceptId() {
    Map<Long, String> result = new LinkedHashMap<>();
    for (ConceptScheme scheme : conceptSchemeService.findAll()) {
      scheme.concepts().forEach(concept -> result.put(concept.id(), concept.code()));
    }
    return result;
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public Observation recordObservation(
      Long dataflowId,
      Map<Long, String> dimensionValuesByDimensionId,
      Map<Long, String> measureValuesByMeasureId,
      String recordedBy) {
    return record(
        dataflowId, dimensionValuesByDimensionId, measureValuesByMeasureId, recordedBy, null);
  }

  /**
   * Corrects the current value of one observation: records a new Alpha vintage for the same series
   * and time period (old vintages stay in the history) with an optional reason, and writes an audit
   * entry with old and new value. A Beta value is never changed in place - the correction becomes
   * visible to Ökonomen only after promotion.
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public Observation correctObservation(
      Long dataflowId,
      Long seriesId,
      String timePeriod,
      Map<Long, String> measureValuesByMeasureId,
      String comment,
      String performedBy) {
    Series series = findSeriesById(seriesId);
    if (!series.dataflowId().equals(dataflowId)) {
      throw new IllegalArgumentException("Series " + seriesId + " is not part of " + dataflowId);
    }
    DataStructureDefinition dsd = dsdService.findById(dataflowService.findById(dataflowId).dsdId());
    List<Dimension> keyDimensions =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() != DataType.DATE)
            .sorted(Comparator.comparingInt(Dimension::position))
            .toList();
    String[] keyValues = series.seriesKey().split("\\.", -1);
    Map<Long, String> dimensionValues = new LinkedHashMap<>();
    for (int i = 0; i < keyDimensions.size(); i++) {
      dimensionValues.put(keyDimensions.get(i).id(), keyValues[i]);
    }
    dsd.dimensions().stream()
        .filter(d -> d.dataType() == DataType.DATE)
        .findFirst()
        .ifPresent(d -> dimensionValues.put(d.id(), timePeriod));

    String before =
        observationRepository
            .findCurrentOne(dataflowId, seriesId, timePeriod, ObservationStage.ALPHA.name())
            .or(
                () ->
                    observationRepository.findCurrentOne(
                        dataflowId, seriesId, timePeriod, ObservationStage.BETA.name()))
            .map(Observation::measureValuesJson)
            .orElse("(none)");
    String reason = comment == null || comment.isBlank() ? null : comment.trim();
    Observation corrected =
        record(dataflowId, dimensionValues, measureValuesByMeasureId, performedBy, reason);
    auditLogService.record(
        dataflowId,
        "CORRECT_OBSERVATION",
        performedBy,
        series.seriesKey()
            + " "
            + timePeriod
            + ": "
            + before
            + " -> "
            + corrected.measureValuesJson()
            + (reason == null ? "" : " (" + reason + ")"));
    return corrected;
  }

  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public Optional<Observation> findCurrentOne(
      Long dataflowId, Long seriesId, String timePeriod, ObservationStage stage) {
    return observationRepository.findCurrentOne(dataflowId, seriesId, timePeriod, stage.name());
  }

  private Observation record(
      Long dataflowId,
      Map<Long, String> dimensionValuesByDimensionId,
      Map<Long, String> measureValuesByMeasureId,
      String recordedBy,
      String comment) {
    Dataflow dataflow = dataflowService.findById(dataflowId);
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());

    Dimension timeDimension =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() == DataType.DATE)
            .findFirst()
            .orElse(null);
    List<Dimension> keyDimensions =
        dsd.dimensions().stream()
            .filter(d -> timeDimension == null || !d.id().equals(timeDimension.id()))
            .sorted(Comparator.comparingInt(Dimension::position))
            .toList();

    StringBuilder seriesKey = new StringBuilder();
    for (Dimension dimension : keyDimensions) {
      String value = dimensionValuesByDimensionId.get(dimension.id());
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("Missing value for dimension " + dimension.id());
      }
      if (dimension.codelistId() != null) {
        requireValidCode(dimension.codelistId(), value);
      }
      if (!seriesKey.isEmpty()) {
        seriesKey.append('.');
      }
      seriesKey.append(value);
    }

    String timePeriod = "";
    if (timeDimension != null) {
      timePeriod = dimensionValuesByDimensionId.get(timeDimension.id());
      if (timePeriod == null || timePeriod.isBlank()) {
        throw new IllegalArgumentException("Missing time period value");
      }
    }

    for (Measure measure : dsd.measures()) {
      String value = measureValuesByMeasureId.get(measure.id());
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("Missing value for measure " + measure.id());
      }
      if (measure.dataType() == DataType.NUMBER) {
        try {
          Double.parseDouble(value);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(
              "Measure " + measure.id() + " is not a number: " + value);
        }
      }
    }

    Series series =
        seriesRepository
            .findByDataflowIdAndSeriesKey(dataflowId, seriesKey.toString())
            .orElseGet(
                () -> seriesRepository.save(Series.createNew(dataflowId, seriesKey.toString())));

    // a re-delivered, unchanged value is not a revision - keep the current vintage instead of
    // closing it, otherwise every scheduled re-import clutters the history with identical values
    Optional<Observation> currentAlpha =
        observationRepository.findCurrentOne(
            dataflowId, series.id(), timePeriod, ObservationStage.ALPHA.name());
    if (comment == null
        && currentAlpha.isPresent()
        && parseMeasureValues(currentAlpha.get().measureValuesJson())
            .equals(measureValuesByMeasureId)) {
      return currentAlpha.get();
    }

    String measureValuesJson = toJson(measureValuesByMeasureId);
    Instant now = Instant.now();
    observationRepository.closeCurrentVintage(
        dataflowId, series.id(), timePeriod, ObservationStage.ALPHA.name(), now);
    return observationRepository.save(
        Observation.createNew(
            dataflowId,
            series.id(),
            timePeriod,
            ObservationStage.ALPHA,
            measureValuesJson,
            now,
            recordedBy,
            comment));
  }

  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public List<Observation> findCurrent(Long dataflowId, ObservationStage stage) {
    return observationRepository.findCurrent(dataflowId, stage.name());
  }

  /**
   * One page of current observations, filtered in SQL by stage, time period range and key dimension
   * codes (keyed by the 1-based position of the dimension in the series key). Non-Datenmanager may
   * only ask for Beta.
   */
  @PreAuthorize(
      "hasRole('DATENMANAGER') or (#stages.size() == 1"
          + " and #stages.contains(T(de.sdmxdw.data.observation.ObservationStage).BETA))")
  public ObservationPage findCurrentPage(
      Long dataflowId,
      Set<ObservationStage> stages,
      Map<Integer, List<String>> keyFilters,
      String fromPeriod,
      String toPeriod,
      int page,
      int size) {
    return findCurrentPage(dataflowId, stages, keyFilters, fromPeriod, toPeriod, null, page, size);
  }

  /**
   * @param keySearch case-insensitive substring of the series key (null = all)
   */
  @PreAuthorize(
      "hasRole('DATENMANAGER') or (#stages.size() == 1"
          + " and #stages.contains(T(de.sdmxdw.data.observation.ObservationStage).BETA))")
  public ObservationPage findCurrentPage(
      Long dataflowId,
      Set<ObservationStage> stages,
      Map<Integer, List<String>> keyFilters,
      String fromPeriod,
      String toPeriod,
      String keySearch,
      int page,
      int size) {
    if (stages.isEmpty()) {
      throw new IllegalArgumentException("At least one stage is required");
    }
    int safePage = Math.max(page, 0);
    long total =
        observationQueryRepository.countCurrent(
            dataflowId, stages, keyFilters, fromPeriod, toPeriod, keySearch);
    List<ObservationQueryRepository.ObservationListRow> rows =
        observationQueryRepository.findCurrent(
            dataflowId, stages, keyFilters, fromPeriod, toPeriod, keySearch, size, safePage * size);
    return new ObservationPage(rows, total, safePage, size);
  }

  public record ObservationPage(
      List<ObservationQueryRepository.ObservationListRow> rows, long total, int page, int size) {
    public int totalPages() {
      return (int) Math.ceil(total / (double) size);
    }
  }

  /**
   * Page of series summaries (first/last period, count, latest values) for the explorer's series
   * lists, filtered by key codes and a series key substring - aggregated in SQL.
   */
  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public SeriesPage findSeriesPage(
      Long dataflowId,
      ObservationStage stage,
      Map<Integer, List<String>> keyFilters,
      String keySearch,
      int page,
      int size) {
    int safePage = Math.max(page, 0);
    long total = observationQueryRepository.countSeries(dataflowId, stage, keyFilters, keySearch);
    List<ObservationQueryRepository.SeriesSummary> rows =
        observationQueryRepository.findSeriesSummaries(
            dataflowId, stage, keyFilters, keySearch, size, safePage * size);
    return new SeriesPage(rows, total, safePage, size);
  }

  /**
   * Facet counts for every key dimension (1-based position -> code -> series count), each computed
   * with the selections of the <em>other</em> dimensions, so choosing a code narrows the rest.
   */
  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public Map<Integer, Map<String, Long>> findKeyFacets(
      Long dataflowId,
      ObservationStage stage,
      int keyDimensionCount,
      Map<Integer, List<String>> keyFilters,
      String keySearch) {
    Map<Integer, Map<String, Long>> facets = new java.util.LinkedHashMap<>();
    for (int position = 1; position <= keyDimensionCount; position++) {
      Map<Integer, List<String>> others = new java.util.HashMap<>(keyFilters);
      others.remove(position);
      facets.put(
          position,
          observationQueryRepository.facetCounts(dataflowId, stage, position, others, keySearch));
    }
    return facets;
  }

  /** Alpha values a promotion would publish (new or changed compared to Beta). */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public PendingPage findPendingChanges(
      Long dataflowId,
      Map<Integer, List<String>> keyFilters,
      String keySearch,
      int page,
      int size) {
    int safePage = Math.max(page, 0);
    return new PendingPage(
        observationQueryRepository.findPending(
            dataflowId, keyFilters, keySearch, size, safePage * size),
        observationQueryRepository.countPending(dataflowId, keyFilters, keySearch),
        safePage,
        size);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public long countPendingChanges(Long dataflowId) {
    return observationQueryRepository.countPending(dataflowId, Map.of(), null);
  }

  public record PendingPage(
      List<ObservationQueryRepository.PendingChange> rows, long total, int page, int size) {
    public int totalPages() {
      return (int) Math.ceil(total / (double) size);
    }
  }

  public record SeriesPage(
      List<ObservationQueryRepository.SeriesSummary> rows, long total, int page, int size) {
    public int totalPages() {
      return (int) Math.ceil(total / (double) size);
    }
  }

  /** All vintages of all time periods of one series, ordered by period and validity. */
  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public List<Observation> findSeriesHistory(Long seriesId, ObservationStage stage) {
    return observationRepository.findSeriesHistory(seriesId, stage.name());
  }

  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public List<Observation> findHistory(Long seriesId, String timePeriod, ObservationStage stage) {
    return observationRepository.findHistory(seriesId, timePeriod, stage.name());
  }

  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public List<Observation> findAsOf(Long dataflowId, ObservationStage stage, Instant asOf) {
    return observationRepository.findAsOf(dataflowId, stage.name(), asOf);
  }

  /**
   * Like {@link #findCurrent} but restricted to a known set of series ids, so a selective SDMX-REST
   * key doesn't force a full scan of every observation in the dataflow.
   */
  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public List<Observation> findCurrentForSeriesIds(
      Long dataflowId, ObservationStage stage, Collection<Long> seriesIds) {
    if (seriesIds.isEmpty()) {
      return List.of();
    }
    return observationRepository.findCurrentForSeriesIds(dataflowId, stage.name(), seriesIds);
  }

  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public List<Observation> findAsOfForSeriesIds(
      Long dataflowId, ObservationStage stage, Collection<Long> seriesIds, Instant asOf) {
    if (seriesIds.isEmpty()) {
      return List.of();
    }
    return observationRepository.findAsOfForSeriesIds(dataflowId, stage.name(), seriesIds, asOf);
  }

  public List<Series> findSeriesByDataflow(Long dataflowId) {
    return seriesRepository.findByDataflowId(dataflowId);
  }

  public Series findSeriesById(Long seriesId) {
    return seriesRepository.findById(seriesId).orElseThrow();
  }

  public Map<Long, String> parseMeasureValues(String measureValuesJson) {
    try {
      Map<String, String> raw =
          objectMapper.readValue(measureValuesJson, new TypeReference<Map<String, String>>() {});
      Map<Long, String> result = new LinkedHashMap<>();
      raw.forEach((key, value) -> result.put(Long.valueOf(key), value));
      return result;
    } catch (Exception e) {
      throw new IllegalStateException("Corrupt measure values JSON: " + measureValuesJson, e);
    }
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public PromotionResult promoteToBeta(Long dataflowId, String performedBy) {
    List<Observation> currentAlpha =
        observationRepository.findCurrent(dataflowId, ObservationStage.ALPHA.name());
    int promoted = 0;
    int unchanged = 0;
    Instant now = Instant.now();
    for (Observation alpha : currentAlpha) {
      Optional<Observation> currentBeta =
          observationRepository.findCurrentOne(
              dataflowId, alpha.seriesId(), alpha.timePeriod(), ObservationStage.BETA.name());
      if (currentBeta.isPresent()
          && currentBeta.get().measureValuesJson().equals(alpha.measureValuesJson())) {
        unchanged++;
        continue;
      }
      observationRepository.closeCurrentVintage(
          dataflowId, alpha.seriesId(), alpha.timePeriod(), ObservationStage.BETA.name(), now);
      observationRepository.save(
          Observation.createNew(
              dataflowId,
              alpha.seriesId(),
              alpha.timePeriod(),
              ObservationStage.BETA,
              alpha.measureValuesJson(),
              now,
              "promotion:" + performedBy,
              alpha.comment()));
      promoted++;
    }
    auditLogService.record(
        dataflowId,
        "PROMOTE_ALPHA_TO_BETA",
        performedBy,
        promoted + " promoted, " + unchanged + " unchanged");
    return new PromotionResult(promoted, unchanged);
  }

  private void requireValidCode(Long codelistId, String code) {
    boolean exists =
        codelistService.findById(codelistId).codes().stream().anyMatch(c -> c.code().equals(code));
    if (!exists) {
      throw new IllegalArgumentException("Unknown code '" + code + "' for codelist " + codelistId);
    }
  }

  private String toJson(Map<Long, String> measureValuesByMeasureId) {
    try {
      Map<String, String> stringKeyed = new LinkedHashMap<>();
      measureValuesByMeasureId.forEach((key, value) -> stringKeyed.put(key.toString(), value));
      return objectMapper.writeValueAsString(stringKeyed);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize measure values", e);
    }
  }

  public record PromotionResult(int promoted, int unchanged) {}
}
