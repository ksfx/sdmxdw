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

  public List<String> reportingPeriods(Long flowId, String stage) {
    return jdbc.queryForList(
        "SELECT DISTINCT reporting_period FROM microdata_record WHERE microdata_flow_id = :flowId"
            + " AND stage = :stage AND valid_to IS NULL ORDER BY reporting_period",
        new MapSqlParameterSource("flowId", flowId).addValue("stage", stage),
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
  public List<MicrodataRecord> findPage(MicrodataQuery query, Map<String, FacetSlot> facetByField) {
    List<MicrodataRecord> rows = new ArrayList<>();
    stream(query, facetByField, rows::add);
    return rows;
  }

  /**
   * Value counts of one indexed (facet) field under the query, for the explorer's pickers. The
   * caller removes the field's own filter so its alternatives stay visible.
   */
  public Map<String, Long> facetCounts(
      MicrodataQuery query, Map<String, FacetSlot> facetByField, FacetSlot slot) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    String column = slot.name() + "_value";
    String sql =
        "SELECT "
            + column
            + " AS v, count(*) AS n FROM microdata_record"
            + where(query, facetByField, params)
            + " AND "
            + column
            + " IS NOT NULL GROUP BY 1";
    Map<String, Long> counts = new LinkedHashMap<>();
    jdbc.query(
        sql,
        params,
        rs -> {
          counts.put(rs.getString("v"), rs.getLong("n"));
        });
    return counts;
  }

  /**
   * Current Alpha records whose payload differs from the current Beta record (or that have none):
   * what {@code MicrodataRecordService.promoteToBeta} would publish - same JSON comparison.
   */
  public List<PendingRecord> findPending(
      MicrodataQuery query, Map<String, FacetSlot> facetByField, int limit, int offset) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    String sql =
        "SELECT a.*, b.payload_json AS beta_payload"
            + pendingFrom(query, facetByField, params)
            + " ORDER BY a.reporting_period, a.entity_id LIMIT :limit OFFSET :offset";
    params.addValue("limit", limit).addValue("offset", offset);
    return jdbc.query(
        sql, params, (rs, i) -> new PendingRecord(map(rs), rs.getString("beta_payload")));
  }

  public long countPending(MicrodataQuery query, Map<String, FacetSlot> facetByField) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    Long count =
        jdbc.queryForObject(
            "SELECT count(*)" + pendingFrom(query, facetByField, params), params, Long.class);
    return count == null ? 0 : count;
  }

  private static String pendingFrom(
      MicrodataQuery query, Map<String, FacetSlot> facetByField, MapSqlParameterSource params) {
    // the filter conditions use unqualified columns; run them on the Alpha side in a subquery
    String alpha =
        "SELECT * FROM microdata_record"
            + where(query.withStage(MicrodataStage.ALPHA), facetByField, params);
    return " FROM ("
        + alpha
        + ") a LEFT JOIN microdata_record b ON b.microdata_flow_id = a.microdata_flow_id"
        + " AND b.entity_id = a.entity_id AND b.reporting_period = a.reporting_period"
        + " AND b.stage = 'BETA' AND b.valid_to IS NULL"
        + " WHERE b.payload_json IS DISTINCT FROM a.payload_json";
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
}
