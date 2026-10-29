package de.sdmxdw.microdata;

import de.sdmxdw.data.BulkLoadError;
import de.sdmxdw.data.BulkLoadResult;
import de.sdmxdw.data.audit.AuditLogService;
import de.sdmxdw.structure.codelist.Code;
import de.sdmxdw.structure.codelist.CodelistService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a delivery of microdata records into the Alpha stage of a flow, validated exactly like
 * {@link MicrodataRecordService#ingestRecord} (required fields, codelists, references), written
 * set-based through a staging table. A record is delivered completely: fields missing in the
 * delivery become empty, groups without rows become empty lists. Unchanged records keep their
 * current vintage.
 */
@Service
public class MicrodataBulkLoader {

  private static final int MAX_ERRORS = 100;

  private final MicrodataFlowService flowService;
  private final MicrodataRecordService recordService;
  private final CodelistService codelistService;
  private final AuditLogService auditLogService;
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public MicrodataBulkLoader(
      MicrodataFlowService flowService,
      MicrodataRecordService recordService,
      CodelistService codelistService,
      AuditLogService auditLogService,
      JdbcTemplate jdbc) {
    this.flowService = flowService;
    this.recordService = recordService;
    this.codelistService = codelistService;
    this.auditLogService = auditLogService;
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  /** One delivered record, assembled from the main row and its group rows. */
  public record Record(
      String file,
      int rowNumber,
      String entityId,
      String reportingPeriod,
      Map<String, String> scalars,
      Map<String, List<Map<String, String>>> groups) {}

  /**
   * @param deliveredEntities entities delivered in the same step (flow id -> "entity|period"),
   *     accepted as reference targets although not yet written
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public BulkLoadResult load(
      Long flowId,
      List<Record> records,
      Map<Long, Set<String>> deliveredEntities,
      String recordedBy) {
    MicrodataFlow flow = flowService.findById(flowId);
    String flowRef = flow.agencyId() + ":" + flow.artefactId() + "(" + flow.version() + ")";
    List<FieldDefinition> fields = flowService.findFields(flowId);
    List<BulkLoadError> errors = new ArrayList<>();
    List<Staged> staged = new ArrayList<>();

    Map<Long, Set<String>> codes = new HashMap<>();
    fields.stream()
        .map(FieldDefinition::codelistId)
        .filter(id -> id != null)
        .distinct()
        .forEach(
            id ->
                codes.put(
                    id,
                    codelistService.findById(id).codes().stream()
                        .map(Code::code)
                        .collect(Collectors.toSet())));
    Set<String> periods =
        records.stream()
            .map(Record::reportingPeriod)
            .filter(p -> p != null)
            .collect(Collectors.toSet());
    Map<Long, Set<String>> existing = new HashMap<>();
    fields.stream()
        .map(FieldDefinition::referencedFlowId)
        .filter(id -> id != null)
        .distinct()
        .forEach(
            target -> {
              Set<String> keys = new HashSet<>(currentAlphaKeys(target, periods));
              keys.addAll(deliveredEntities.getOrDefault(target, Set.of()));
              existing.put(target, keys);
            });

    Set<String> scalarCodes = new HashSet<>();
    Map<String, List<FieldDefinition>> groupFields = new LinkedHashMap<>();
    for (FieldDefinition f : fields) {
      if (f.groupCode() == null) {
        scalarCodes.add(f.fieldCode());
      } else {
        groupFields.computeIfAbsent(f.groupCode(), g -> new ArrayList<>()).add(f);
      }
    }
    Set<String> reported = new HashSet<>();
    Set<String> seen = new HashSet<>();
    for (Record r : records) {
      if (errors.size() >= MAX_ERRORS) {
        break;
      }
      int before = errors.size();
      for (String column : r.scalars().keySet()) {
        if (!scalarCodes.contains(column) && reported.add(r.file() + "|" + column)) {
          errors.add(new BulkLoadError(r.file(), 0, "Unknown field " + column + " for " + flowRef));
        }
      }
      for (String group : r.groups().keySet()) {
        if (!groupFields.containsKey(group) && reported.add(r.file() + "|group|" + group)) {
          errors.add(new BulkLoadError(r.file(), 0, "Unknown group " + group + " for " + flowRef));
        }
      }
      if (blank(r.entityId()) || blank(r.reportingPeriod())) {
        errors.add(
            new BulkLoadError(
                r.file(), r.rowNumber(), "ENTITY_ID and REPORTING_PERIOD are required"));
        continue;
      }
      if (!seen.add(r.entityId() + "|" + r.reportingPeriod())) {
        errors.add(
            new BulkLoadError(
                r.file(),
                r.rowNumber(),
                "Duplicate record "
                    + r.entityId()
                    + " @ "
                    + r.reportingPeriod()
                    + " in the delivery"));
        continue;
      }
      Map<String, String> scalars = new LinkedHashMap<>();
      Map<FacetSlot, String> facets = new HashMap<>();
      for (FieldDefinition f : fields) {
        if (f.groupCode() != null) {
          continue;
        }
        String value = clean(r.scalars().get(f.fieldCode()));
        check(f, value, r, codes, existing, errors);
        scalars.put(f.fieldCode(), value);
        if (f.facetSlot() != null) {
          facets.put(f.facetSlot(), value);
        }
      }
      Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
      groupFields.forEach(
          (group, gf) -> {
            List<Map<String, String>> items = new ArrayList<>();
            for (Map<String, String> item : r.groups().getOrDefault(group, List.of())) {
              Map<String, String> cleaned = new LinkedHashMap<>();
              for (FieldDefinition f : gf) {
                String value = clean(item.get(f.fieldCode()));
                check(f, value, r, codes, existing, errors);
                cleaned.put(f.fieldCode(), value);
              }
              items.add(cleaned);
            }
            groups.put(group, items);
          });
      if (errors.size() == before) {
        staged.add(
            new Staged(
                r.entityId(),
                r.reportingPeriod(),
                facets.get(FacetSlot.facet1),
                facets.get(FacetSlot.facet2),
                facets.get(FacetSlot.facet3),
                facets.get(FacetSlot.facet4),
                recordService.toJson(new MicrodataPayload(scalars, groups))));
      }
    }
    if (!errors.isEmpty()) {
      return new BulkLoadResult(
          flowRef,
          records.size(),
          0,
          0,
          errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors);
    }
    if (staged.isEmpty()) {
      return new BulkLoadResult(flowRef, 0, 0, 0, List.of());
    }
    int[] counts = write(flowId, staged, recordedBy);
    auditLogService.recordMicrodata(
        flowId,
        "PROCESS_LOAD",
        recordedBy,
        records.size() + " records: " + counts[0] + " written, " + counts[1] + " unchanged");
    return new BulkLoadResult(flowRef, records.size(), counts[0], counts[1], List.of());
  }

  private static void check(
      FieldDefinition f,
      String value,
      Record r,
      Map<Long, Set<String>> codes,
      Map<Long, Set<String>> existing,
      List<BulkLoadError> errors) {
    if (value == null) {
      if (f.required()) {
        errors.add(
            new BulkLoadError(r.file(), r.rowNumber(), "Missing required field " + f.fieldCode()));
      }
      return;
    }
    if (f.codelistId() != null && !codes.get(f.codelistId()).contains(value)) {
      errors.add(
          new BulkLoadError(
              r.file(), r.rowNumber(), "Unknown code '" + value + "' for " + f.fieldCode()));
    }
    if (f.referencedFlowId() != null
        && !existing.get(f.referencedFlowId()).contains(value + "|" + r.reportingPeriod())) {
      errors.add(
          new BulkLoadError(
              r.file(),
              r.rowNumber(),
              f.fieldCode()
                  + " references unknown entity '"
                  + value
                  + "' for "
                  + r.reportingPeriod()));
    }
  }

  private Collection<String> currentAlphaKeys(Long flowId, Set<String> periods) {
    if (periods.isEmpty()) {
      return List.of();
    }
    return named.queryForList(
        "SELECT entity_id || '|' || reporting_period FROM microdata_record"
            + " WHERE microdata_flow_id = :flow AND stage = 'ALPHA' AND valid_to IS NULL"
            + " AND reporting_period IN (:periods)",
        new MapSqlParameterSource("flow", flowId).addValue("periods", periods),
        String.class);
  }

  private record Staged(
      String entityId,
      String reportingPeriod,
      String facet1,
      String facet2,
      String facet3,
      String facet4,
      String payload) {}

  /**
   * @return {written, unchanged}
   */
  private int[] write(Long flowId, List<Staged> staged, String recordedBy) {
    jdbc.execute("DROP TABLE IF EXISTS md_stage");
    jdbc.execute(
        "CREATE TEMP TABLE md_stage (entity_id TEXT, reporting_period TEXT, f1 TEXT, f2 TEXT,"
            + " f3 TEXT, f4 TEXT, payload TEXT) ON COMMIT DROP");
    jdbc.batchUpdate(
        "INSERT INTO md_stage VALUES (?, ?, ?, ?, ?, ?, ?)",
        staged,
        1000,
        (ps, s) -> {
          ps.setString(1, s.entityId());
          ps.setString(2, s.reportingPeriod());
          ps.setString(3, s.facet1());
          ps.setString(4, s.facet2());
          ps.setString(5, s.facet3());
          ps.setString(6, s.facet4());
          ps.setString(7, s.payload());
        });
    MapSqlParameterSource p =
        new MapSqlParameterSource("flow", flowId)
            .addValue("now", Timestamp.from(Instant.now()))
            .addValue("by", recordedBy);
    String join =
        " m.microdata_flow_id = :flow AND m.entity_id = st.entity_id"
            + " AND m.reporting_period = st.reporting_period AND m.stage = 'ALPHA'"
            + " AND m.valid_to IS NULL";
    Integer unchanged =
        named.queryForObject(
            "SELECT count(*) FROM md_stage st JOIN microdata_record m ON"
                + join
                + " WHERE m.payload_json::jsonb = st.payload::jsonb",
            p,
            Integer.class);
    named.update(
        "UPDATE microdata_record m SET valid_to = :now FROM md_stage st WHERE"
            + join
            + " AND m.payload_json::jsonb <> st.payload::jsonb",
        p);
    int written =
        named.update(
            "INSERT INTO microdata_record (microdata_flow_id, entity_id, reporting_period, stage,"
                + " facet1_value, facet2_value, facet3_value, facet4_value, payload_json,"
                + " valid_from, recorded_by)"
                + " SELECT :flow, st.entity_id, st.reporting_period, 'ALPHA', st.f1, st.f2, st.f3,"
                + " st.f4, st.payload, :now, :by FROM md_stage st"
                + " WHERE NOT EXISTS (SELECT 1 FROM microdata_record m WHERE"
                + join
                + ")",
            p);
    jdbc.execute("DROP TABLE md_stage");
    return new int[] {written, unchanged == null ? 0 : unchanged};
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String clean(String value) {
    return blank(value) ? null : value.strip();
  }
}
