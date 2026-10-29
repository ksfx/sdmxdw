package de.sdmxdw.microdata;

import de.sdmxdw.data.audit.AuditLogService;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
import de.sdmxdw.structure.dsd.Dimension;
import de.sdmxdw.structure.dsd.Measure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class MicrodataRecordService {

  private final MicrodataRecordRepository microdataRecordRepository;
  private final MicrodataFlowService microdataFlowService;
  private final CodelistService codelistService;
  private final DataflowService dataflowService;
  private final DataStructureDefinitionService dsdService;
  private final ObservationService observationService;
  private final ObjectMapper objectMapper;
  private final MicrodataQueryRepository microdataQueryRepository;
  private final AuditLogService auditLogService;

  public MicrodataRecordService(
      MicrodataRecordRepository microdataRecordRepository,
      MicrodataFlowService microdataFlowService,
      CodelistService codelistService,
      DataflowService dataflowService,
      DataStructureDefinitionService dsdService,
      ObservationService observationService,
      ObjectMapper objectMapper,
      MicrodataQueryRepository microdataQueryRepository,
      AuditLogService auditLogService) {
    this.microdataRecordRepository = microdataRecordRepository;
    this.microdataFlowService = microdataFlowService;
    this.codelistService = codelistService;
    this.dataflowService = dataflowService;
    this.dsdService = dsdService;
    this.observationService = observationService;
    this.objectMapper = objectMapper;
    this.microdataQueryRepository = microdataQueryRepository;
    this.auditLogService = auditLogService;
  }

  @PostAuthorize("returnObject.stage().name() == 'BETA' or hasRole('DATENMANAGER')")
  public MicrodataRecord findById(Long recordId) {
    return microdataRecordRepository.findById(recordId).orElseThrow();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public MicrodataRecord ingestRecord(
      Long flowId,
      String entityId,
      String reportingPeriod,
      Map<String, String> fieldValuesByCode,
      String recordedBy) {
    return ingestRecord(flowId, entityId, reportingPeriod, fieldValuesByCode, Map.of(), recordedBy);
  }

  /**
   * Like the four-arg {@link #ingestRecord}, but also accepts instances of repeating field groups
   * (group code -> list of instances, each instance a field-code-to-value map), e.g. several
   * collaterals on one loan.
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public MicrodataRecord ingestRecord(
      Long flowId,
      String entityId,
      String reportingPeriod,
      Map<String, String> fieldValuesByCode,
      Map<String, List<Map<String, String>>> groupValuesByGroupCode,
      String recordedBy) {
    return ingest(
        flowId,
        entityId,
        reportingPeriod,
        fieldValuesByCode,
        groupValuesByGroupCode,
        recordedBy,
        null);
  }

  /**
   * Manual correction of one record (#16): saves a new Alpha vintage with the given field values,
   * validated exactly like an ingest, and writes an audit entry with the changed fields.
   *
   * @param comment optional reason, shown in the history and carried along on promotion
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public MicrodataRecord correctRecord(
      Long flowId,
      String entityId,
      String reportingPeriod,
      Map<String, String> fieldValuesByCode,
      Map<String, List<Map<String, String>>> groupValuesByGroupCode,
      String comment,
      String performedBy) {
    MicrodataRecord before =
        findCurrentOne(flowId, entityId, reportingPeriod, MicrodataStage.ALPHA)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No current Alpha record " + entityId + " @ " + reportingPeriod));
    String reason = comment == null || comment.isBlank() ? null : comment.strip();
    MicrodataRecord after =
        ingest(
            flowId,
            entityId,
            reportingPeriod,
            fieldValuesByCode,
            groupValuesByGroupCode,
            performedBy,
            reason);
    auditLogService.recordMicrodata(
        flowId,
        "CORRECT_RECORD",
        performedBy,
        entityId
            + " @ "
            + reportingPeriod
            + ": "
            + describeChanges(parsePayload(before.payloadJson()), parsePayload(after.payloadJson()))
            + (reason == null ? "" : " (" + reason + ")"));
    return after;
  }

  /** "FIELD: old -> new; GROUP: 2 -> 3 entries" - for audit entries and the pending list. */
  public String describeChanges(MicrodataPayload before, MicrodataPayload after) {
    List<String> changes = new ArrayList<>();
    java.util.Set<String> codes = new java.util.LinkedHashSet<>(before.scalars().keySet());
    codes.addAll(after.scalars().keySet());
    for (String code : codes) {
      String oldValue = before.scalars().get(code);
      String newValue = after.scalars().get(code);
      if (!java.util.Objects.equals(blankToNull(oldValue), blankToNull(newValue))) {
        changes.add(
            code
                + ": "
                + (oldValue == null ? "-" : oldValue)
                + " -> "
                + (newValue == null ? "-" : newValue));
      }
    }
    java.util.Set<String> groupCodes = new java.util.LinkedHashSet<>(before.groups().keySet());
    groupCodes.addAll(after.groups().keySet());
    for (String group : groupCodes) {
      List<Map<String, String>> oldItems = before.groups().getOrDefault(group, List.of());
      List<Map<String, String>> newItems = after.groups().getOrDefault(group, List.of());
      if (!oldItems.equals(newItems)) {
        changes.add(
            group
                + ": "
                + (oldItems.size() == newItems.size()
                    ? "entries changed"
                    : oldItems.size() + " -> " + newItems.size() + " entries"));
      }
    }
    return changes.isEmpty() ? "no changes" : String.join("; ", changes);
  }

  private MicrodataRecord ingest(
      Long flowId,
      String entityId,
      String reportingPeriod,
      Map<String, String> fieldValuesByCode,
      Map<String, List<Map<String, String>>> groupValuesByGroupCode,
      String recordedBy,
      String comment) {
    List<FieldDefinition> fields = microdataFlowService.findFields(flowId);
    Map<FacetSlot, String> facetValues = new LinkedHashMap<>();
    Map<String, String> scalars = new LinkedHashMap<>();
    Map<String, List<FieldDefinition>> groupFieldsByGroupCode = new LinkedHashMap<>();
    for (FieldDefinition field : fields) {
      if (field.groupCode() != null) {
        groupFieldsByGroupCode
            .computeIfAbsent(field.groupCode(), g -> new ArrayList<>())
            .add(field);
        continue;
      }
      String value = fieldValuesByCode.get(field.fieldCode());
      validateFieldValue(field, value, reportingPeriod);
      scalars.put(field.fieldCode(), value);
      if (field.facetSlot() != null) {
        facetValues.put(field.facetSlot(), value);
      }
    }

    Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
    for (Map.Entry<String, List<FieldDefinition>> entry : groupFieldsByGroupCode.entrySet()) {
      String groupCode = entry.getKey();
      for (Map<String, String> instance :
          groupValuesByGroupCode.getOrDefault(groupCode, List.of())) {
        for (FieldDefinition groupField : entry.getValue()) {
          validateFieldValue(groupField, instance.get(groupField.fieldCode()), reportingPeriod);
        }
      }
      groups.put(groupCode, groupValuesByGroupCode.getOrDefault(groupCode, List.of()));
    }

    String payloadJson = toJson(new MicrodataPayload(scalars, groups));
    Instant now = Instant.now();
    microdataRecordRepository.closeCurrentVintage(
        flowId, entityId, reportingPeriod, MicrodataStage.ALPHA.name(), now);
    return microdataRecordRepository.save(
        MicrodataRecord.createNew(
            flowId,
            entityId,
            reportingPeriod,
            MicrodataStage.ALPHA,
            facetValues.get(FacetSlot.facet1),
            facetValues.get(FacetSlot.facet2),
            facetValues.get(FacetSlot.facet3),
            facetValues.get(FacetSlot.facet4),
            payloadJson,
            now,
            recordedBy,
            comment));
  }

  private void validateFieldValue(FieldDefinition field, String value, String reportingPeriod) {
    if (field.required() && (value == null || value.isBlank())) {
      throw new IllegalArgumentException("Missing required field " + field.fieldCode());
    }
    if (value == null) {
      return;
    }
    if (field.codelistId() != null) {
      requireValidCode(field.codelistId(), value);
    }
    if (field.referencedFlowId() != null) {
      requireValidReference(field.referencedFlowId(), value, reportingPeriod, field.fieldCode());
    }
  }

  private void requireValidReference(
      Long referencedFlowId, String entityId, String reportingPeriod, String fieldCode) {
    if (findCurrentOne(referencedFlowId, entityId, reportingPeriod, MicrodataStage.ALPHA)
        .isEmpty()) {
      throw new IllegalArgumentException(
          "Field "
              + fieldCode
              + " references unknown entity '"
              + entityId
              + "' in flow "
              + referencedFlowId
              + " for period "
              + reportingPeriod);
    }
  }

  /**
   * Resolves a reference field's value to the record it points at - used both to validate
   * references at ingest time and to render them as links in the record browser.
   */
  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public Optional<MicrodataRecord> findCurrentOne(
      Long flowId, String entityId, String reportingPeriod, MicrodataStage stage) {
    return microdataRecordRepository.findCurrentOne(
        flowId, entityId, reportingPeriod, stage.name());
  }

  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public PageResult findCurrentFiltered(
      Long flowId,
      MicrodataStage stage,
      String reportingPeriod,
      String facet1,
      String facet2,
      String facet3,
      String facet4,
      String q,
      int page,
      int size) {
    int offset = Math.max(page, 0) * size;
    List<MicrodataRecord> records =
        microdataRecordRepository.findCurrentFiltered(
            flowId,
            stage.name(),
            blankToNull(reportingPeriod),
            blankToNull(facet1),
            blankToNull(facet2),
            blankToNull(facet3),
            blankToNull(facet4),
            blankToNull(q),
            size,
            offset);
    long total =
        microdataRecordRepository.countCurrentFiltered(
            flowId,
            stage.name(),
            blankToNull(reportingPeriod),
            blankToNull(facet1),
            blankToNull(facet2),
            blankToNull(facet3),
            blankToNull(facet4),
            blankToNull(q));
    return new PageResult(records, total, page, size);
  }

  /** Feeds the reporting-period selector at the top of the microdata explorer. */
  /**
   * Streams the records matching an API query to the consumer, ordered by reporting period and
   * entity id. Read-only transaction so PostgreSQL can use a cursor instead of loading everything.
   */
  @PreAuthorize("#query.stage().name() == 'BETA' or hasRole('DATENMANAGER')")
  @Transactional(readOnly = true)
  public void streamRecords(MicrodataQuery query, Consumer<MicrodataRecord> consumer) {
    microdataQueryRepository.stream(query, facetsByField(query.flowId()), consumer);
  }

  @PreAuthorize("#query.stage().name() == 'BETA' or hasRole('DATENMANAGER')")
  public long countRecords(MicrodataQuery query) {
    return microdataQueryRepository.count(query, facetsByField(query.flowId()));
  }

  /** One page of records and the total, for the explorer's record list. */
  @PreAuthorize("#query.stage().name() == 'BETA' or hasRole('DATENMANAGER')")
  public RecordPage findRecordPage(MicrodataQuery query) {
    Map<String, FacetSlot> facets = facetsByField(query.flowId());
    return new RecordPage(
        microdataQueryRepository.findPage(query, facets),
        microdataQueryRepository.count(query, facets),
        query.limit() == null ? 0 : query.offset() / Math.max(query.limit(), 1),
        query.limit() == null ? Integer.MAX_VALUE : query.limit());
  }

  /**
   * Value counts per indexed field (field code -> value -> records), each under the filters of the
   * other fields, for the explorer's pickers.
   */
  @PreAuthorize("#query.stage().name() == 'BETA' or hasRole('DATENMANAGER')")
  public Map<String, Map<String, Long>> findFieldFacets(MicrodataQuery query) {
    Map<String, FacetSlot> facets = facetsByField(query.flowId());
    Map<String, Map<String, Long>> result = new LinkedHashMap<>();
    for (Map.Entry<String, FacetSlot> facet : facets.entrySet()) {
      Map<String, List<String>> others = new LinkedHashMap<>(query.fieldFilters());
      others.remove(facet.getKey());
      MicrodataQuery withoutOwn =
          new MicrodataQuery(
              query.flowId(),
              query.stage(),
              query.reportingPeriods(),
              query.asOf(),
              others,
              query.freeText(),
              null,
              0);
      result.put(
          facet.getKey(),
          microdataQueryRepository.facetCounts(withoutOwn, facets, facet.getValue()));
    }
    return result;
  }

  /** Alpha records a promotion would publish (new or changed compared to Beta). */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public PendingPage findPendingChanges(MicrodataQuery query) {
    Map<String, FacetSlot> facets = facetsByField(query.flowId());
    int size = query.limit() == null ? 50 : query.limit();
    return new PendingPage(
        microdataQueryRepository.findPending(query, facets, size, query.offset()),
        microdataQueryRepository.countPending(query, facets),
        query.offset() / Math.max(size, 1),
        size);
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public long countPendingChanges(Long flowId) {
    return microdataQueryRepository.countPending(
        new MicrodataQuery(flowId, MicrodataStage.ALPHA, List.of(), null, Map.of(), null, 0),
        Map.of());
  }

  public record RecordPage(List<MicrodataRecord> records, long total, int page, int size) {
    public int totalPages() {
      return (int) Math.ceil(total / (double) size);
    }
  }

  public record PendingPage(
      List<MicrodataQueryRepository.PendingRecord> rows, long total, int page, int size) {
    public int totalPages() {
      return (int) Math.ceil(total / (double) size);
    }
  }

  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public List<String> findReportingPeriods(Long flowId, MicrodataStage stage) {
    return microdataQueryRepository.reportingPeriods(flowId, stage.name());
  }

  private Map<String, FacetSlot> facetsByField(Long flowId) {
    Map<String, FacetSlot> facets = new HashMap<>();
    for (FieldDefinition field : microdataFlowService.findFields(flowId)) {
      if (field.facetSlot() != null) {
        facets.put(field.fieldCode(), field.facetSlot());
      }
    }
    return facets;
  }

  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public List<String> findDistinctReportingPeriods(MicrodataStage stage) {
    return microdataRecordRepository.findDistinctReportingPeriods(stage.name());
  }

  @PreAuthorize("#stage.name() == 'BETA' or hasRole('DATENMANAGER')")
  public List<MicrodataRecord> findHistory(
      Long flowId, String entityId, String reportingPeriod, MicrodataStage stage) {
    return microdataRecordRepository.findHistory(flowId, entityId, reportingPeriod, stage.name());
  }

  public MicrodataPayload parsePayload(String payloadJson) {
    try {
      MicrodataPayload parsed = objectMapper.readValue(payloadJson, MicrodataPayload.class);
      return new MicrodataPayload(
          parsed.scalars() == null ? Map.of() : parsed.scalars(),
          parsed.groups() == null ? Map.of() : parsed.groups());
    } catch (Exception e) {
      throw new IllegalStateException("Corrupt payload JSON: " + payloadJson, e);
    }
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public PromotionResult promoteToBeta(Long flowId, String performedBy) {
    List<MicrodataRecord> currentAlpha =
        microdataRecordRepository.findAllCurrent(flowId, MicrodataStage.ALPHA.name());
    int promoted = 0;
    int unchanged = 0;
    Instant now = Instant.now();
    for (MicrodataRecord alpha : currentAlpha) {
      Optional<MicrodataRecord> currentBeta =
          microdataRecordRepository.findCurrentOne(
              flowId, alpha.entityId(), alpha.reportingPeriod(), MicrodataStage.BETA.name());
      if (currentBeta.isPresent() && currentBeta.get().payloadJson().equals(alpha.payloadJson())) {
        unchanged++;
        continue;
      }
      microdataRecordRepository.closeCurrentVintage(
          flowId, alpha.entityId(), alpha.reportingPeriod(), MicrodataStage.BETA.name(), now);
      microdataRecordRepository.save(
          MicrodataRecord.createNew(
              flowId,
              alpha.entityId(),
              alpha.reportingPeriod(),
              MicrodataStage.BETA,
              alpha.facet1Value(),
              alpha.facet2Value(),
              alpha.facet3Value(),
              alpha.facet4Value(),
              alpha.payloadJson(),
              now,
              "promotion:" + performedBy,
              alpha.comment()));
      promoted++;
    }
    auditLogService.recordMicrodata(
        flowId,
        "PROMOTE_ALPHA_TO_BETA",
        performedBy,
        promoted + " promoted, " + unchanged + " unchanged");
    return new PromotionResult(promoted, unchanged);
  }

  /**
   * Bridges back to the SDMX cube model: groups current Beta records by their (up to four) facet
   * values and reporting period, counts them, and writes the counts as normal observations into the
   * linked Dataflow - so the existing explorer/chart/REST-API tooling keeps working on top of
   * microdata without any changes. Assumes the linked DSD's non-date dimensions line up
   * positionally with facet1..facet4 and has exactly one count-style measure.
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public AggregationResult aggregateAndPublish(Long flowId, String performedBy) {
    MicrodataFlow flow = microdataFlowService.findById(flowId);
    if (flow.linkedDataflowId() == null) {
      throw new IllegalStateException("Microdata flow has no linked dataflow for aggregation");
    }
    Dataflow dataflow = dataflowService.findById(flow.linkedDataflowId());
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    List<Dimension> keyDimensions =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() != DataType.DATE)
            .sorted(Comparator.comparingInt(Dimension::position))
            .toList();
    Dimension timeDimension =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() == DataType.DATE)
            .findFirst()
            .orElse(null);
    Long measureId =
        dsd.measures().stream()
            .map(Measure::id)
            .min(Comparator.naturalOrder())
            .orElseThrow(() -> new IllegalStateException("Target DSD has no measure"));

    List<MicrodataRecord> currentBeta =
        microdataRecordRepository.findAllCurrent(flowId, MicrodataStage.BETA.name());
    Map<List<String>, Long> counts =
        currentBeta.stream()
            .collect(
                Collectors.groupingBy(
                    r ->
                        List.of(
                            nullToEmpty(r.facet1Value()),
                            nullToEmpty(r.facet2Value()),
                            nullToEmpty(r.facet3Value()),
                            nullToEmpty(r.facet4Value()),
                            r.reportingPeriod()),
                    Collectors.counting()));

    int written = 0;
    for (Map.Entry<List<String>, Long> entry : counts.entrySet()) {
      List<String> key = entry.getKey();
      Map<Long, String> dimensionValues = new LinkedHashMap<>();
      for (int i = 0; i < keyDimensions.size(); i++) {
        dimensionValues.put(keyDimensions.get(i).id(), key.get(i));
      }
      if (timeDimension != null) {
        dimensionValues.put(timeDimension.id(), key.get(4));
      }
      Map<Long, String> measureValues = Map.of(measureId, entry.getValue().toString());
      observationService.recordObservation(
          flow.linkedDataflowId(), dimensionValues, measureValues, "aggregation:" + performedBy);
      written++;
    }
    ObservationService.PromotionResult promotion =
        observationService.promoteToBeta(flow.linkedDataflowId(), performedBy);
    auditLogService.recordMicrodata(
        flowId,
        "AGGREGATE_AND_PUBLISH",
        performedBy,
        written + " aggregate observations written to dataflow " + flow.linkedDataflowId());
    return new AggregationResult(written, promotion.promoted(), promotion.unchanged());
  }

  private void requireValidCode(Long codelistId, String code) {
    boolean exists =
        codelistService.findById(codelistId).codes().stream().anyMatch(c -> c.code().equals(code));
    if (!exists) {
      throw new IllegalArgumentException("Unknown code '" + code + "' for codelist " + codelistId);
    }
  }

  /**
   * The stored payload format; shared with {@link MicrodataBulkLoader} so text comparisons match.
   */
  String toJson(MicrodataPayload payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize field values", e);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  public record PageResult(List<MicrodataRecord> records, long totalCount, int page, int size) {}

  public record PromotionResult(int promoted, int unchanged) {}

  /**
   * Serializable because it travels through a redirect as a flash attribute, which Spring Session
   * persists to the (JDBC-backed) session store.
   */
  public record AggregationResult(int groupsWritten, int promoted, int unchanged)
      implements java.io.Serializable {}
}
