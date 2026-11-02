package de.sdmxdw.microdata;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Streaming read access for the microdata REST API. Rows are handed to a consumer one by one
 * (PostgreSQL cursor with a fetch size, requires a surrounding transaction), so exporting a large
 * flow does not hold all records in memory.
 */
@Repository
public class MicrodataQueryRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public MicrodataQueryRepository(DataSource dataSource) {
    JdbcTemplate template = new JdbcTemplate(dataSource);
    template.setFetchSize(1000);
    this.jdbc = new NamedParameterJdbcTemplate(template);
  }

  public void stream(
      MicrodataQuery query, Map<String, FacetSlot> facetByField, Consumer<MicrodataRecord> rows) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    StringBuilder sql =
        new StringBuilder("SELECT * FROM microdata_record")
            .append(where(query, facetByField, params));
    sql.append(" ORDER BY reporting_period, entity_id");
    if (query.limit() != null) {
      sql.append(" LIMIT :limit");
      params.addValue("limit", query.limit());
    }
    if (query.offset() > 0) {
      sql.append(" OFFSET :offset");
      params.addValue("offset", query.offset());
    }
    jdbc.query(
        sql.toString(),
        params,
        rs -> {
          rows.accept(map(rs));
        });
  }

  public long count(MicrodataQuery query, Map<String, FacetSlot> facetByField) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM microdata_record" + where(query, facetByField, params),
            params,
            Long.class);
    return count == null ? 0 : count;
  }

  /** Reporting periods of a flow, read with a loose index scan: one index probe per period. */
  public List<String> reportingPeriods(Long flowId, String stage) {
    return jdbc.queryForList(
        "WITH RECURSIVE p AS ("
            + " (SELECT reporting_period FROM microdata_record WHERE stage = :stage"
            + " AND microdata_flow_id = :flowId AND valid_to IS NULL"
            + " ORDER BY reporting_period LIMIT 1)"
            + " UNION ALL SELECT (SELECT r.reporting_period FROM microdata_record r"
            + " WHERE r.stage = :stage AND r.microdata_flow_id = :flowId AND r.valid_to IS NULL"
            + " AND r.reporting_period > p.reporting_period ORDER BY r.reporting_period LIMIT 1)"
            + " FROM p WHERE p.reporting_period IS NOT NULL)"
            + " SELECT reporting_period FROM p WHERE reporting_period IS NOT NULL",
        new MapSqlParameterSource("flowId", flowId).addValue("stage", stage),
        String.class);
  }

  /** Reporting periods of all flows, newest first; a loose index scan over flows and periods. */
  public List<String> reportingPeriods(String stage) {
    return jdbc.queryForList(
        "WITH RECURSIVE p AS ("
            + " (SELECT microdata_flow_id AS f, reporting_period AS rp FROM microdata_record"
            + " WHERE stage = :stage AND valid_to IS NULL"
            + " ORDER BY microdata_flow_id, reporting_period LIMIT 1)"
            + " UNION ALL SELECT n.f, n.rp FROM p, LATERAL (SELECT r.microdata_flow_id AS f,"
            + " r.reporting_period AS rp FROM microdata_record r WHERE r.stage = :stage"
            + " AND r.valid_to IS NULL AND (r.microdata_flow_id, r.reporting_period) > (p.f, p.rp)"
            + " ORDER BY r.microdata_flow_id, r.reporting_period LIMIT 1) n)"
            + " SELECT DISTINCT rp FROM p ORDER BY rp DESC",
        new MapSqlParameterSource("stage", stage),
        String.class);
  }

  private static String where(
      MicrodataQuery query, Map<String, FacetSlot> facetByField, MapSqlParameterSource params) {
    StringBuilder sql = new StringBuilder(" WHERE microdata_flow_id = :flowId AND stage = :stage");
    params.addValue("flowId", query.flowId()).addValue("stage", query.stage().name());
    if (query.asOf() == null) {
      sql.append(" AND valid_to IS NULL");
    } else {
      sql.append(" AND valid_from <= :asOf AND (valid_to IS NULL OR valid_to > :asOf)");
      params.addValue("asOf", Timestamp.from(query.asOf()));
    }
    if (!query.reportingPeriods().isEmpty()) {
      sql.append(" AND reporting_period IN (:periods)");
      params.addValue("periods", query.reportingPeriods());
    }
    if (query.freeText() != null && !query.freeText().isBlank()) {
      sql.append(
          " AND (entity_id ILIKE :freeText ESCAPE '\\' OR payload_json ILIKE :freeText ESCAPE '\\')");
      params.addValue("freeText", "%" + likeEscape(query.freeText().trim()) + "%");
    }
    appendFieldFilters(sql, query.fieldFilters(), facetByField, params);
    return sql.toString();
  }

  private static String likeEscape(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static void appendFieldFilters(
      StringBuilder sql,
      Map<String, List<String>> fieldFilters,
      Map<String, FacetSlot> facetByField,
      MapSqlParameterSource params) {
    int i = 0;
    for (Map.Entry<String, List<String>> filter : fieldFilters.entrySet()) {
      String param = "f" + i++;
      FacetSlot facet = facetByField.get(filter.getKey());
      if (facet != null) {
        // enum constant, not user text - safe to inline
        sql.append(" AND ").append(facet.name()).append("_value IN (:").append(param).append(')');
      } else {
        sql.append(" AND (payload_json::jsonb -> 'scalars' ->> :")
            .append(param)
            .append("_code) IN (:")
            .append(param)
            .append(')');
        params.addValue(param + "_code", filter.getKey());
      }
      params.addValue(param, filter.getValue());
    }
  }

  /** One page of records (for the explorer's record list). */
  /**
   * One page of records. The page is chosen on the keys alone (index-only for the usual filters),
   * then only its records are read - deep pages no longer read every skipped record.
   */
  public List<MicrodataRecord> findPage(MicrodataQuery query, Map<String, FacetSlot> facetByField) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    StringBuilder keys =
        new StringBuilder("SELECT id, reporting_period, entity_id FROM microdata_record")
            .append(where(query, facetByField, params))
            .append(" ORDER BY reporting_period, entity_id");
    if (query.limit() != null) {
      keys.append(" LIMIT :limit");
      params.addValue("limit", query.limit());
    }
    if (query.offset() > 0) {
      keys.append(" OFFSET :offset");
      params.addValue("offset", query.offset());
    }
    List<MicrodataRecord> rows = new ArrayList<>();
    jdbc.query(
        "SELECT r.* FROM microdata_record r JOIN ("
            + keys
            + ") page ON page.id = r.id ORDER BY page.reporting_period, page.entity_id",
        params,
        rs -> {
          rows.add(map(rs));
        });
    return rows;
  }

  /**
   * Value counts of one indexed (facet) field under the query, for the explorer's pickers. The
   * caller removes the field's own filter so its alternatives stay visible.
   */
  /**
   * Values of an indexed field with their record counts, most frequent first.
   *
   * @param limit at most this many values
   * @param search only values containing this text (case-insensitive), or null
   */
  public Map<String, Long> facetCounts(
      MicrodataQuery query,
      Map<String, FacetSlot> facetByField,
      FacetSlot slot,
      int limit,
      String search) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    String column = slot.name() + "_value";
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(column)
            .append(" AS v, count(*) AS n FROM microdata_record")
            .append(where(query, facetByField, params))
            .append(" AND ")
            .append(column)
            .append(" IS NOT NULL");
    if (search != null && !search.isBlank()) {
      sql.append(" AND ").append(column).append(" ILIKE :search ESCAPE '\\'");
      params.addValue("search", "%" + likeEscape(search.strip()) + "%");
    }
    sql.append(" GROUP BY 1 ORDER BY n DESC, v LIMIT :limit");
    params.addValue("limit", limit);
    Map<String, Long> counts = new LinkedHashMap<>();
    jdbc.query(
        sql.toString(),
        params,
        rs -> {
          counts.put(rs.getString("v"), rs.getLong("n"));
        });
    return counts;
  }

  /**
   * Current Alpha records whose payload differs from the current Beta record (or that have none):
   * what {@code MicrodataRecordService.promoteToBeta} would publish. Read from the table {@code
   * microdata_pending} maintained on write (V19); only the shown page is joined with the records.
   */
  public List<PendingRecord> findPending(
      MicrodataQuery query, Map<String, FacetSlot> facetByField, int limit, int offset) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    String sql =
        "SELECT a.*, b.payload_json AS beta_payload FROM microdata_record a JOIN ("
            + pendingKeys(query, facetByField, params)
            + " ORDER BY p.reporting_period, p.entity_id LIMIT :limit OFFSET :offset) page"
            + " ON page.record_id = a.id"
            + " LEFT JOIN microdata_record b ON b.microdata_flow_id = a.microdata_flow_id"
            + " AND b.entity_id = a.entity_id AND b.reporting_period = a.reporting_period"
            + " AND b.stage = 'BETA' AND b.valid_to IS NULL"
            + " ORDER BY a.reporting_period, a.entity_id";
    params.addValue("limit", limit).addValue("offset", offset);
    return jdbc.query(
        sql, params, (rs, i) -> new PendingRecord(map(rs), rs.getString("beta_payload")));
  }

  public long countPending(MicrodataQuery query, Map<String, FacetSlot> facetByField) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM (" + pendingKeys(query, facetByField, params) + ") k",
            params,
            Long.class);
    return count == null ? 0 : count;
  }

  /**
   * {@code record_id, reporting_period, entity_id} of the pending records matching the query; only
   * field and text filters need the records themselves.
   */
  private static String pendingKeys(
      MicrodataQuery query, Map<String, FacetSlot> facetByField, MapSqlParameterSource params) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT p.record_id, p.reporting_period, p.entity_id FROM microdata_pending p"
                + " WHERE p.microdata_flow_id = :flowId");
    params.addValue("flowId", query.flowId());
    if (!query.reportingPeriods().isEmpty()) {
      sql.append(" AND p.reporting_period IN (:periods)");
      params.addValue("periods", query.reportingPeriods());
    }
    boolean filtered =
        !query.fieldFilters().isEmpty()
                && query.fieldFilters().values().stream().anyMatch(v -> !v.isEmpty())
            || query.freeText() != null && !query.freeText().isBlank();
    if (filtered) {
      MapSqlParameterSource inner = new MapSqlParameterSource();
      sql.append(" AND p.record_id IN (SELECT id FROM microdata_record")
          .append(where(query.withStage(MicrodataStage.ALPHA), facetByField, inner))
          .append(')');
      for (String name : inner.getParameterNames()) {
        params.addValue(name, inner.getValue(name));
      }
    }
    return sql.toString();
  }

  /**
   * @param betaPayloadJson null = new record, never published
   */
  public record PendingRecord(MicrodataRecord alpha, String betaPayloadJson) {}

  static MicrodataRecord map(ResultSet rs) throws SQLException {
    Timestamp validTo = rs.getTimestamp("valid_to");
    return new MicrodataRecord(
        rs.getLong("id"),
        rs.getLong("microdata_flow_id"),
        rs.getString("entity_id"),
        rs.getString("reporting_period"),
        MicrodataStage.valueOf(rs.getString("stage")),
        rs.getString("facet1_value"),
        rs.getString("facet2_value"),
        rs.getString("facet3_value"),
        rs.getString("facet4_value"),
        rs.getString("payload_json"),
        rs.getTimestamp("valid_from").toInstant(),
        validTo == null ? null : validTo.toInstant(),
        rs.getString("recorded_by"),
        rs.getString("comment"));
  }

  /**
   * Publishes the current Alpha records of a flow set-based (#17): records whose current Beta
   * vintage has the same payload stay, changed Beta vintages are closed, and the Alpha records
   * without an equal Beta vintage are inserted as new Beta vintages.
   *
   * @return {promoted, unchanged}
   */
  public int[] promoteAll(Long flowId, Timestamp now, String recordedBy) {
    MapSqlParameterSource p =
        new MapSqlParameterSource("flow", flowId).addValue("now", now).addValue("by", recordedBy);
    String current =
        " b.microdata_flow_id = a.microdata_flow_id AND b.entity_id = a.entity_id"
            + " AND b.reporting_period = a.reporting_period AND b.stage = 'BETA'"
            + " AND b.valid_to IS NULL";
    String alpha = " a.microdata_flow_id = :flow AND a.stage = 'ALPHA' AND a.valid_to IS NULL";
    Integer unchanged =
        jdbc.queryForObject(
            "SELECT count(*) FROM microdata_record a JOIN microdata_record b ON"
                + current
                + " WHERE"
                + alpha
                + " AND b.payload_json = a.payload_json",
            p,
            Integer.class);
    jdbc.update(
        "UPDATE microdata_record b SET valid_to = :now FROM microdata_record a WHERE"
            + alpha
            + " AND"
            + current
            + " AND b.payload_json <> a.payload_json",
        p);
    int promoted =
        jdbc.update(
            "INSERT INTO microdata_record (microdata_flow_id, entity_id, reporting_period, stage,"
                + " facet1_value, facet2_value, facet3_value, facet4_value, payload_json,"
                + " valid_from, recorded_by, comment)"
                + " SELECT a.microdata_flow_id, a.entity_id, a.reporting_period, 'BETA',"
                + " a.facet1_value, a.facet2_value, a.facet3_value, a.facet4_value,"
                + " a.payload_json, :now, 'promotion:' || :by, a.comment"
                + " FROM microdata_record a WHERE"
                + alpha
                + " AND NOT EXISTS (SELECT 1 FROM microdata_record b WHERE"
                + current
                + ")",
            p);
    // every current Alpha record now equals its Beta record
    jdbc.update("DELETE FROM microdata_pending WHERE microdata_flow_id = :flow", p);
    return new int[] {promoted, unchanged == null ? 0 : unchanged};
  }

  /** Number of current records per facet values and reporting period, for the aggregation. */
  public void countByFacets(Long flowId, String stage, Consumer<FacetCount> consumer) {
    jdbc.query(
        "SELECT coalesce(facet1_value, ''), coalesce(facet2_value, ''), coalesce(facet3_value, ''),"
            + " coalesce(facet4_value, ''), reporting_period, count(*) FROM microdata_record"
            + " WHERE microdata_flow_id = :flow AND stage = :stage AND valid_to IS NULL"
            + " GROUP BY 1, 2, 3, 4, 5",
        new MapSqlParameterSource("flow", flowId).addValue("stage", stage),
        rs -> {
          consumer.accept(
              new FacetCount(
                  List.of(
                      rs.getString(1),
                      rs.getString(2),
                      rs.getString(3),
                      rs.getString(4),
                      rs.getString(5)),
                  rs.getLong(6)));
        });
  }

  /** {@code key}: facet1..facet4 (empty for none) and the reporting period. */
  public record FacetCount(List<String> key, long count) {}
}
