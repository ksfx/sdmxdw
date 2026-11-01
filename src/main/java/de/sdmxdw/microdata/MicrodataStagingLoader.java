package de.sdmxdw.microdata;

import de.sdmxdw.data.BulkLoadError;
import de.sdmxdw.data.audit.AuditLogService;
import de.sdmxdw.structure.codelist.Code;
import de.sdmxdw.structure.codelist.CodelistService;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads microdata deliveries of any size (#17): rows are validated one by one as they are read
 * (required fields, codes, unknown columns) and streamed with {@code COPY} into temporary staging
 * tables, so memory does not grow with the delivery. Checks across rows - duplicates, group rows
 * without their record, references to other flows (also to records of the same delivery) - and the
 * write itself run set-based in PostgreSQL. A record is delivered completely: fields missing in the
 * delivery become empty, groups without rows become empty lists; unchanged records keep their
 * current vintage. The payload is the same JSON text {@link MicrodataRecordService} stores, so text
 * comparisons (pending changes, promotion) stay exact.
 *
 * <p>Use one {@link Session} per delivery inside one transaction: {@link #open}, add rows, then
 * {@link Session#finish}. Rolling back the transaction discards everything (dry run).
 */
@Service
public class MicrodataStagingLoader {

  public static final int MAX_ERRORS = 100;
  private static final int COPY_BATCH = 5_000;

  private final MicrodataFlowService flowService;
  private final CodelistService codelistService;
  private final AuditLogService auditLogService;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public MicrodataStagingLoader(
      MicrodataFlowService flowService,
      CodelistService codelistService,
      AuditLogService auditLogService,
      ObjectMapper objectMapper,
      JdbcTemplate jdbc) {
    this.flowService = flowService;
    this.codelistService = codelistService;
    this.auditLogService = auditLogService;
    this.objectMapper = objectMapper;
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  /** Result per flow, in the order the flows first appeared in the delivery. */
  public record FlowResult(MicrodataFlow flow, int rows, int written, int unchanged) {}

  /** Starts a delivery; must run inside the transaction that also calls {@link Session#finish}. */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public Session open(List<BulkLoadError> errors) {
    jdbc.execute("DROP TABLE IF EXISTS md_load");
    jdbc.execute("DROP TABLE IF EXISTS md_load_group");
    jdbc.execute(
        "CREATE TEMP TABLE md_load (flow_id BIGINT, entity_id TEXT, period TEXT, f1 TEXT,"
            + " f2 TEXT, f3 TEXT, f4 TEXT, scalars TEXT, file TEXT, row_no INT) ON COMMIT DROP");
    jdbc.execute(
        "CREATE TEMP TABLE md_load_group (flow_id BIGINT, entity_id TEXT, period TEXT,"
            + " group_code TEXT, item_no DOUBLE PRECISION, seq INT, item TEXT, file TEXT,"
            + " row_no INT) ON COMMIT DROP");
    return new Session(errors);
  }

  /** Field definitions and codes of one flow, loaded once per delivery. */
  private final class FlowInfo {
    final MicrodataFlow flow;
    final String ref;
    final List<FieldDefinition> scalars = new ArrayList<>();
    final Map<String, List<FieldDefinition>> groups = new LinkedHashMap<>();
    final Map<Long, Set<String>> codes = new HashMap<>();

    FlowInfo(MicrodataFlow flow) {
      this.flow = flow;
      this.ref = flow.agencyId() + ":" + flow.artefactId() + "(" + flow.version() + ")";
      for (FieldDefinition f : flowService.findFields(flow.id())) {
        if (f.groupCode() == null) {
          scalars.add(f);
        } else {
          groups.computeIfAbsent(f.groupCode(), g -> new ArrayList<>()).add(f);
        }
        if (f.codelistId() != null && !codes.containsKey(f.codelistId())) {
          codes.put(
              f.codelistId(),
              codelistService.findById(f.codelistId()).codes().stream()
                  .map(Code::code)
                  .collect(Collectors.toSet()));
        }
      }
    }
  }

  /** One delivery: rows go into the staging tables in batches. */
  public final class Session {

    private final List<BulkLoadError> errors;
    private final Map<Long, FlowInfo> flows = new LinkedHashMap<>();
    // flows in the order of their first record row: the order of the results
    private final Set<Long> recordOrder = new LinkedHashSet<>();
    private final Set<String> reported = new HashSet<>();
    private final StringBuilder mainBuffer = new StringBuilder();
    private final StringBuilder groupBuffer = new StringBuilder();
    private int mainBuffered;
    private int groupBuffered;
    private int groupSeq;

    private Session(List<BulkLoadError> errors) {
      this.errors = errors;
    }

    private FlowInfo info(MicrodataFlow flow) {
      return flows.computeIfAbsent(flow.id(), id -> new FlowInfo(flow));
    }

    private boolean full() {
      return errors.size() >= MAX_ERRORS;
    }

    private void error(String file, int row, String message) {
      if (!full()) {
        errors.add(new BulkLoadError(file, row, message));
      }
    }

    /**
     * Reports columns of a file that the flow (or group) does not define; once per file.
     *
     * @param group null for record rows
     */
    public void columns(String file, MicrodataFlow flow, String group, List<String> columns) {
      FlowInfo info = info(flow);
      if (group != null && !info.groups.containsKey(group)) {
        if (reported.add(file + "|group|" + group)) {
          error(file, 0, "Unknown group " + group + " for " + info.ref);
        }
        return;
      }
      Set<String> known = new HashSet<>();
      (group == null ? info.scalars : info.groups.get(group))
          .forEach(f -> known.add(f.fieldCode()));
      for (String column : columns) {
        if (!KEYS.contains(column)
            && !known.contains(column)
            && reported.add(file + "|" + column)) {
          error(file, 0, "Unknown field " + column + " for " + info.ref);
        }
      }
    }

    /** A record row; {@code values} by column name. */
    public void record(String file, int row, MicrodataFlow flow, Map<String, String> values) {
      if (full()) {
        return;
      }
      FlowInfo info = info(flow);
      recordOrder.add(flow.id());
      String entity = clean(values.get("ENTITY_ID"));
      String period = clean(values.get("REPORTING_PERIOD"));
      if (entity == null || period == null) {
        error(file, row, "ENTITY_ID and REPORTING_PERIOD are required");
        return;
      }
      Map<String, String> scalars = new LinkedHashMap<>();
      String[] facets = new String[4];
      for (FieldDefinition f : info.scalars) {
        String value = clean(values.get(f.fieldCode()));
        check(info, f, value, file, row);
        scalars.put(f.fieldCode(), value);
        if (f.facetSlot() != null) {
          facets[f.facetSlot().ordinal()] = value;
        }
      }
      // staged even when invalid: the checks across rows still see the record
      appendRow(
          mainBuffer,
          String.valueOf(flow.id()),
          entity,
          period,
          facets[0],
          facets[1],
          facets[2],
          facets[3],
          objectMapper.writeValueAsString(scalars),
          file,
          String.valueOf(row));
      if (++mainBuffered >= COPY_BATCH) {
        flushMain();
      }
    }

    /** A row of a repeating group, joined to its record by ENTITY_ID and REPORTING_PERIOD. */
    public void groupRow(
        String file, int row, MicrodataFlow flow, String group, Map<String, String> values) {
      if (full()) {
        return;
      }
      FlowInfo info = info(flow);
      List<FieldDefinition> fields = info.groups.get(group);
      if (fields == null) {
        return; // reported by columns()
      }
      String entity = clean(values.get("ENTITY_ID"));
      String period = clean(values.get("REPORTING_PERIOD"));
      if (entity == null || period == null) {
        error(file, row, "ENTITY_ID and REPORTING_PERIOD are required");
        return;
      }
      Map<String, String> item = new LinkedHashMap<>();
      for (FieldDefinition f : fields) {
        String value = clean(values.get(f.fieldCode()));
        check(info, f, value, file, row);
        item.put(f.fieldCode(), value);
      }
      // items are ordered by ITEM_NO; without one, after the numbered items in delivery order
      double order;
      try {
        order = Double.parseDouble(values.getOrDefault("ITEM_NO", ""));
      } catch (NumberFormatException | NullPointerException e) {
        order = 1_000_000;
      }
      appendRow(
          groupBuffer,
          String.valueOf(flow.id()),
          entity,
          period,
          group,
          Double.toString(order),
          String.valueOf(groupSeq++),
          objectMapper.writeValueAsString(item),
          file,
          String.valueOf(row));
      if (++groupBuffered >= COPY_BATCH) {
        flushGroups();
      }
    }

    private void check(FlowInfo info, FieldDefinition f, String value, String file, int row) {
      if (value == null) {
        if (f.required()) {
          error(file, row, "Missing required field " + f.fieldCode());
        }
        return;
      }
      if (f.codelistId() != null && !info.codes.get(f.codelistId()).contains(value)) {
        error(file, row, "Unknown code '" + value + "' for " + f.fieldCode());
      }
    }

    private void flushMain() {
      copy("COPY md_load FROM STDIN WITH (FORMAT csv)", mainBuffer);
      mainBuffered = 0;
    }

    private void flushGroups() {
      copy("COPY md_load_group FROM STDIN WITH (FORMAT csv)", groupBuffer);
      groupBuffered = 0;
    }

    private void copy(String sql, StringBuilder buffer) {
      if (buffer.isEmpty()) {
        return;
      }
      String data = buffer.toString();
      buffer.setLength(0);
      jdbc.execute(
          (ConnectionCallback<Long>)
              connection -> {
                try {
                  return connection
                      .unwrap(PGConnection.class)
                      .getCopyAPI()
                      .copyIn(sql, new StringReader(data));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }

    /**
     * Checks across rows and writes the delivery into the Alpha stage. Adds errors instead of
     * writing when a check fails; the caller rolls back then.
     */
    public List<FlowResult> finish(String recordedBy) {
      flushMain();
      flushGroups();
      if (full()) {
        return List.of();
      }
      jdbc.execute("CREATE INDEX ON md_load (flow_id, entity_id, period)");
      jdbc.execute("CREATE INDEX ON md_load_group (flow_id, entity_id, period, group_code)");
      jdbc.execute("ANALYZE md_load");
      jdbc.execute("ANALYZE md_load_group");
      checkDuplicates();
      checkOrphanGroupRows();
      for (FlowInfo info : flows.values()) {
        for (FieldDefinition f : info.scalars) {
          if (f.referencedFlowId() != null && !full()) {
            checkReferences(info, f);
          }
        }
      }
      if (!errors.isEmpty()) {
        return List.of();
      }
      return write(recordedBy);
    }

    private void checkDuplicates() {
      jdbc.query(
          "SELECT file, row_no, entity_id, period FROM (SELECT file, row_no, entity_id, period,"
              + " row_number() OVER (PARTITION BY flow_id, entity_id, period ORDER BY file, row_no)"
              + " AS n FROM md_load) d WHERE n > 1 ORDER BY file, row_no LIMIT "
              + MAX_ERRORS,
          rs -> {
            error(
                rs.getString(1),
                rs.getInt(2),
                "Duplicate record "
                    + rs.getString(3)
                    + " @ "
                    + rs.getString(4)
                    + " in the delivery");
          });
    }

    private void checkOrphanGroupRows() {
      jdbc.query(
          "SELECT g.file, g.row_no, g.entity_id, g.period FROM md_load_group g WHERE NOT EXISTS"
              + " (SELECT 1 FROM md_load l WHERE l.flow_id = g.flow_id"
              + " AND l.entity_id = g.entity_id AND l.period = g.period)"
              + " ORDER BY g.file, g.row_no LIMIT "
              + MAX_ERRORS,
          rs -> {
            error(
                rs.getString(1),
                rs.getInt(2),
                "Group row for "
                    + rs.getString(3)
                    + " @ "
                    + rs.getString(4)
                    + " without its main record in the delivery");
          });
    }

    /** A reference must point to a record of the same period: delivered now or current Alpha. */
    private void checkReferences(FlowInfo info, FieldDefinition f) {
      MapSqlParameterSource p =
          new MapSqlParameterSource("flow", info.flow.id())
              .addValue("target", f.referencedFlowId())
              .addValue("field", f.fieldCode());
      named.query(
          "SELECT l.file, l.row_no, r.val, l.period FROM md_load l,"
              + " LATERAL (SELECT l.scalars::jsonb ->> :field AS val) r"
              + " WHERE l.flow_id = :flow AND r.val IS NOT NULL"
              + " AND NOT EXISTS (SELECT 1 FROM md_load t WHERE t.flow_id = :target"
              + " AND t.entity_id = r.val AND t.period = l.period)"
              + " AND NOT EXISTS (SELECT 1 FROM microdata_record m"
              + " WHERE m.microdata_flow_id = :target AND m.entity_id = r.val"
              + " AND m.reporting_period = l.period AND m.stage = 'ALPHA' AND m.valid_to IS NULL)"
              + " ORDER BY l.file, l.row_no LIMIT "
              + MAX_ERRORS,
          p,
          rs -> {
            error(
                rs.getString(1),
                rs.getInt(2),
                f.fieldCode()
                    + " references unknown entity '"
                    + rs.getString(3)
                    + "' for "
                    + rs.getString(4));
          });
    }

    private List<FlowResult> write(String recordedBy) {
      jdbc.execute(
          "CREATE TEMP TABLE md_load_final (flow_id BIGINT, entity_id TEXT, period TEXT,"
              + " f1 TEXT, f2 TEXT, f3 TEXT, f4 TEXT, payload TEXT) ON COMMIT DROP");
      for (FlowInfo info : flows.values()) {
        // {"scalars":{...},"groups":{"G":[{...},...],...}} exactly like Jackson writes the payload
        StringBuilder groups = new StringBuilder("''");
        boolean first = true;
        for (String group : info.groups.keySet()) {
          groups
              .append(" || '")
              .append(first ? "" : ",")
              .append('"')
              .append(group.replace("'", "''").replace("\"", "\\\""))
              .append("\":[' || coalesce((SELECT string_agg(g.item, ',' ORDER BY g.item_no,")
              .append(" g.seq) FROM md_load_group g WHERE g.flow_id = l.flow_id")
              .append(" AND g.entity_id = l.entity_id AND g.period = l.period AND g.group_code = '")
              .append(group.replace("'", "''"))
              .append("'), '') || ']'");
          first = false;
        }
        named.update(
            "INSERT INTO md_load_final SELECT flow_id, entity_id, period, f1, f2, f3, f4,"
                + " '{\"scalars\":' || scalars || ',\"groups\":{' || "
                + groups
                + " || '}}' FROM md_load l WHERE flow_id = :flow",
            new MapSqlParameterSource("flow", info.flow.id()));
      }
      jdbc.execute("CREATE INDEX ON md_load_final (flow_id, entity_id, period)");
      jdbc.execute("ANALYZE md_load_final");

      MapSqlParameterSource p =
          new MapSqlParameterSource("now", Timestamp.from(Instant.now()))
              .addValue("by", recordedBy);
      String join =
          " m.microdata_flow_id = st.flow_id AND m.entity_id = st.entity_id"
              + " AND m.reporting_period = st.period AND m.stage = 'ALPHA' AND m.valid_to IS NULL";
      Map<Long, Integer> rows = counts("SELECT flow_id, count(*) FROM md_load_final GROUP BY 1");
      Map<Long, Integer> unchanged =
          counts(
              "SELECT st.flow_id, count(*) FROM md_load_final st JOIN microdata_record m ON"
                  + join
                  + " WHERE m.payload_json = st.payload GROUP BY 1");
      named.update(
          "UPDATE microdata_record m SET valid_to = :now FROM md_load_final st WHERE"
              + join
              + " AND m.payload_json <> st.payload",
          p);
      Map<Long, Integer> written = new HashMap<>();
      named.query(
          "WITH ins AS (INSERT INTO microdata_record (microdata_flow_id, entity_id,"
              + " reporting_period, stage, facet1_value, facet2_value, facet3_value, facet4_value,"
              + " payload_json, valid_from, recorded_by)"
              + " SELECT st.flow_id, st.entity_id, st.period, 'ALPHA', st.f1, st.f2, st.f3, st.f4,"
              + " st.payload, :now, :by FROM md_load_final st"
              + " WHERE NOT EXISTS (SELECT 1 FROM microdata_record m WHERE"
              + join
              + ") RETURNING microdata_flow_id)"
              + " SELECT microdata_flow_id, count(*) FROM ins GROUP BY 1",
          p,
          rs -> {
            written.put(rs.getLong(1), rs.getInt(2));
          });

      List<FlowResult> results = new ArrayList<>();
      for (Long id : recordOrder) {
        FlowInfo info = flows.get(id);
        if (!rows.containsKey(id)) {
          continue;
        }
        FlowResult r =
            new FlowResult(
                info.flow,
                rows.get(id),
                written.getOrDefault(id, 0),
                unchanged.getOrDefault(id, 0));
        auditLogService.recordMicrodata(
            id,
            "PROCESS_LOAD",
            recordedBy,
            r.rows() + " records: " + r.written() + " written, " + r.unchanged() + " unchanged");
        results.add(r);
      }
      return results;
    }

    private Map<Long, Integer> counts(String sql) {
      Map<Long, Integer> result = new HashMap<>();
      jdbc.query(
          sql,
          rs -> {
            result.put(rs.getLong(1), rs.getInt(2));
          });
      return result;
    }
  }

  /** Columns of the handoff format that are not fields. */
  static final Set<String> KEYS =
      Set.of(
          "STRUCTURE",
          "STRUCTURE_ID",
          "ACTION",
          "DATAFLOW",
          "ENTITY_ID",
          "REPORTING_PERIOD",
          "ITEM_NO");

  /** A CSV line for COPY: every value quoted, null as an unquoted empty field. */
  private static void appendRow(StringBuilder out, String... values) {
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      String v = values[i];
      if (v != null) {
        out.append('"').append(v.replace("\"", "\"\"")).append('"');
      }
    }
    out.append('\n');
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
