package de.sdmxdw.data.observation;

import de.sdmxdw.data.BulkLoadError;
import de.sdmxdw.data.BulkLoadResult;
import de.sdmxdw.data.audit.AuditLogService;
import de.sdmxdw.structure.codelist.Code;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Loads a delivery of observations (rows keyed by SDMX concept code, as in SDMX-CSV) into the Alpha
 * stage of a dataflow. Validation is the same as for a single observation (codes, required
 * dimensions and measures, numbers); writing is set-based through a staging table instead of one
 * round trip per observation. Unchanged re-deliveries keep their current vintage, like {@link
 * ObservationService#recordObservation}.
 */
@Service
public class ObservationBulkLoader {

  private static final int MAX_ERRORS = 100;

  private final DataflowService dataflowService;
  private final DataStructureDefinitionService dsdService;
  private final ConceptSchemeService conceptSchemeService;
  private final CodelistService codelistService;
  private final AuditLogService auditLogService;
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final ObjectMapper objectMapper;

  public ObservationBulkLoader(
      DataflowService dataflowService,
      DataStructureDefinitionService dsdService,
      ConceptSchemeService conceptSchemeService,
      CodelistService codelistService,
      AuditLogService auditLogService,
      JdbcTemplate jdbc,
      ObjectMapper objectMapper) {
    this.dataflowService = dataflowService;
    this.dsdService = dsdService;
    this.conceptSchemeService = conceptSchemeService;
    this.codelistService = codelistService;
    this.auditLogService = auditLogService;
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.objectMapper = objectMapper;
  }

  /** A delivered row: values by column name (concept code), with its origin for error messages. */
  public record Row(String file, int rowNumber, Map<String, String> values) {}

  /**
   * Validates the rows and, if valid, writes them. Must run inside a transaction (all targets of a
   * process step are loaded together).
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  @Transactional
  public BulkLoadResult load(Long dataflowId, List<Row> rows, String recordedBy) {
    Dataflow dataflow = dataflowService.findById(dataflowId);
    String flowRef =
        dataflow.agencyId() + ":" + dataflow.artefactId() + "(" + dataflow.version() + ")";
    Prepared prepared = validate(dataflow, rows);
    if (!prepared.errors().isEmpty()) {
      return new BulkLoadResult(flowRef, rows.size(), 0, 0, prepared.errors());
    }
    if (prepared.staged().isEmpty()) {
      return new BulkLoadResult(flowRef, 0, 0, 0, List.of());
    }
    int[] counts = write(dataflowId, prepared.staged(), recordedBy);
    auditLogService.record(
        dataflowId,
        "PROCESS_LOAD",
        recordedBy,
        rows.size() + " rows: " + counts[0] + " written, " + counts[1] + " unchanged");
    return new BulkLoadResult(flowRef, rows.size(), counts[0], counts[1], List.of());
  }

  private record Staged(String seriesKey, String timePeriod, String json) {}

  private record Prepared(List<Staged> staged, List<BulkLoadError> errors) {}

  private Prepared validate(Dataflow dataflow, List<Row> rows) {
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    Map<Long, String> codes = conceptCodes();
    Dimension timeDimension =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() == DataType.DATE)
            .findFirst()
            .orElse(null);
    List<Dimension> keyDimensions =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() != DataType.DATE)
            .sorted(Comparator.comparingInt(Dimension::position))
            .toList();
    // same iteration order as ObservationService, so the stored JSON text is identical
    List<Measure> measures = new ArrayList<>(dsd.measures());
    Map<Long, Set<String>> allowedCodes = new HashMap<>();
    for (Dimension d : keyDimensions) {
      if (d.codelistId() != null) {
        allowedCodes.put(
            d.id(),
            codelistService.findById(d.codelistId()).codes().stream()
                .map(Code::code)
                .collect(Collectors.toSet()));
      }
    }
    Set<String> known = new HashSet<>(KNOWN_STRUCTURE_COLUMNS);
    dsd.dimensions().forEach(d -> known.add(codes.get(d.conceptId())));
    measures.forEach(m -> known.add(codes.get(m.conceptId())));

    List<BulkLoadError> errors = new ArrayList<>();
    List<Staged> staged = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Set<String> reportedColumns = new HashSet<>();
    for (Row row : rows) {
      if (errors.size() >= MAX_ERRORS) {
        break;
      }
      for (String column : row.values().keySet()) {
        if (!known.contains(column) && reportedColumns.add(row.file() + "|" + column)) {
          errors.add(
              new BulkLoadError(
                  row.file(),
                  0,
                  "Unknown column " + column + " for dataflow " + dataflow.artefactId()));
        }
      }
      List<String> keyParts = new ArrayList<>();
      boolean rowValid = true;
      for (Dimension d : keyDimensions) {
        String code = codes.get(d.conceptId());
        String value = trim(row.values().get(code));
        if (value == null) {
          errors.add(new BulkLoadError(row.file(), row.rowNumber(), "Missing value for " + code));
          rowValid = false;
        } else if (allowedCodes.containsKey(d.id()) && !allowedCodes.get(d.id()).contains(value)) {
          errors.add(
              new BulkLoadError(
                  row.file(), row.rowNumber(), "Unknown code '" + value + "' for " + code));
          rowValid = false;
        }
        keyParts.add(value);
      }
      String timePeriod = "";
      if (timeDimension != null) {
        String code = codes.get(timeDimension.conceptId());
        timePeriod = trim(row.values().get(code));
        if (timePeriod == null) {
          errors.add(new BulkLoadError(row.file(), row.rowNumber(), "Missing value for " + code));
          rowValid = false;
        }
      }
      Map<String, String> json = new LinkedHashMap<>();
      for (Measure m : measures) {
        String code = codes.get(m.conceptId());
        String value = trim(row.values().get(code));
        if (value == null) {
          errors.add(new BulkLoadError(row.file(), row.rowNumber(), "Missing value for " + code));
          rowValid = false;
          continue;
        }
        if (m.dataType() == DataType.NUMBER) {
          try {
            Double.parseDouble(value);
          } catch (NumberFormatException e) {
            errors.add(
                new BulkLoadError(
                    row.file(), row.rowNumber(), code + " is not a number: '" + value + "'"));
            rowValid = false;
          }
        }
        json.put(m.id().toString(), value);
      }
      if (!rowValid) {
        continue;
      }
      String seriesKey = String.join(".", keyParts);
      if (!seen.add(seriesKey + "@" + timePeriod)) {
        errors.add(
            new BulkLoadError(
                row.file(),
                row.rowNumber(),
                "Duplicate observation " + seriesKey + " @ " + timePeriod + " in the delivery"));
        continue;
      }
      staged.add(new Staged(seriesKey, timePeriod == null ? "" : timePeriod, toJson(json)));
    }
    return new Prepared(
        staged, errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors);
  }

  /**
   * @return {written, unchanged}
   */
  private int[] write(Long dataflowId, List<Staged> staged, String recordedBy) {
    jdbc.execute("DROP TABLE IF EXISTS obs_stage");
    jdbc.execute(
        "CREATE TEMP TABLE obs_stage (series_key TEXT, time_period TEXT, json TEXT)"
            + " ON COMMIT DROP");
    jdbc.batchUpdate(
        "INSERT INTO obs_stage VALUES (?, ?, ?)",
        staged,
        1000,
        (ps, s) -> {
          ps.setString(1, s.seriesKey());
          ps.setString(2, s.timePeriod());
          ps.setString(3, s.json());
        });
    MapSqlParameterSource p =
        new MapSqlParameterSource("df", dataflowId)
            .addValue("now", Timestamp.from(Instant.now()))
            .addValue("by", recordedBy);
    named.update(
        "INSERT INTO series (dataflow_id, series_key) SELECT DISTINCT :df, series_key"
            + " FROM obs_stage ON CONFLICT (dataflow_id, series_key) DO NOTHING",
        p);
    String current =
        " FROM obs_stage st JOIN series s ON s.dataflow_id = :df AND s.series_key = st.series_key"
            + " JOIN observation o ON o.dataflow_id = :df AND o.series_id = s.id"
            + " AND o.time_period = st.time_period AND o.stage = 'ALPHA' AND o.valid_to IS NULL";
    Integer unchanged =
        named.queryForObject(
            "SELECT count(*)" + current + " WHERE o.measure_values_json::jsonb = st.json::jsonb",
            p,
            Integer.class);
    named.update(
        "UPDATE observation o SET valid_to = :now FROM obs_stage st"
            + " JOIN series s ON s.dataflow_id = :df AND s.series_key = st.series_key"
            + " WHERE o.dataflow_id = :df AND o.series_id = s.id AND o.time_period = st.time_period"
            + " AND o.stage = 'ALPHA' AND o.valid_to IS NULL"
            + " AND o.measure_values_json::jsonb <> st.json::jsonb",
        p);
    int written =
        named.update(
            "INSERT INTO observation (dataflow_id, series_id, time_period, stage,"
                + " measure_values_json, valid_from, recorded_by)"
                + " SELECT :df, s.id, st.time_period, 'ALPHA', st.json, :now, :by FROM obs_stage st"
                + " JOIN series s ON s.dataflow_id = :df AND s.series_key = st.series_key"
                + " WHERE NOT EXISTS (SELECT 1 FROM observation o WHERE o.dataflow_id = :df"
                + " AND o.series_id = s.id AND o.time_period = st.time_period"
                + " AND o.stage = 'ALPHA' AND o.valid_to IS NULL)",
            p);
    jdbc.execute("DROP TABLE obs_stage");
    return new int[] {written, unchanged == null ? 0 : unchanged};
  }

  static final Set<String> KNOWN_STRUCTURE_COLUMNS =
      Set.of("STRUCTURE", "STRUCTURE_ID", "ACTION", "DATAFLOW");

  private Map<Long, String> conceptCodes() {
    Map<Long, String> result = new HashMap<>();
    for (ConceptScheme scheme : conceptSchemeService.findAll()) {
      scheme.concepts().forEach(c -> result.put(c.id(), c.code()));
    }
    return result;
  }

  private String toJson(Map<String, String> values) {
    try {
      return objectMapper.writeValueAsString(values);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String trim(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
