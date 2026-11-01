package de.sdmxdw.data.observation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Filtered, paged listing of current observations for the Datenmanager data page. Dimension filters
 * work on the stored series key ("Q.DEMO01._T.39.26") via split_part, so every filter is evaluated
 * in SQL and only one page of rows ever leaves the database - the previous page loaded all
 * observations of a dataflow (32 MB HTML for DF_KRED).
 */
@Repository
public class ObservationQueryRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public ObservationQueryRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param keyFilters 1-based position of the key dimension in the series key -> accepted codes
   */
  public List<ObservationListRow> findCurrent(
      Long dataflowId,
      Collection<ObservationStage> stages,
      Map<Integer, List<String>> keyFilters,
      String fromPeriod,
      String toPeriod,
      String keySearch,
      int limit,
      int offset) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    String sql =
        "SELECT o.id, o.series_id, s.series_key, o.time_period, o.stage, o.measure_values_json,"
            + " o.valid_from, o.recorded_by"
            + where(dataflowId, stages, keyFilters, fromPeriod, toPeriod, keySearch, params)
            + " ORDER BY s.series_key, o.time_period, o.stage LIMIT :limit OFFSET :offset";
    params.addValue("limit", limit).addValue("offset", offset);
    return jdbc.query(
        sql,
        params,
        (rs, i) ->
            new ObservationListRow(
                rs.getLong("id"),
                rs.getLong("series_id"),
                rs.getString("series_key"),
                rs.getString("time_period"),
                ObservationStage.valueOf(rs.getString("stage")),
                rs.getString("measure_values_json"),
                rs.getTimestamp("valid_from").toInstant(),
                rs.getString("recorded_by")));
  }

  public long countCurrent(
      Long dataflowId,
      Collection<ObservationStage> stages,
      Map<Integer, List<String>> keyFilters,
      String fromPeriod,
      String toPeriod,
      String keySearch) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    String sql =
        "SELECT count(*)"
            + where(dataflowId, stages, keyFilters, fromPeriod, toPeriod, keySearch, params);
    Long count = jdbc.queryForObject(sql, params, Long.class);
    return count == null ? 0 : count;
  }

  /**
   * One row per series with first/last period, observation count and the latest measure values -
   * aggregated in SQL, so the explorer's series lists never load the observations themselves.
   *
   * @param keySearch case-insensitive substring of the series key (null = all)
   */
  public List<SeriesSummary> findSeriesSummaries(
      Long dataflowId,
      ObservationStage stage,
      Map<Integer, List<String>> keyFilters,
      String keySearch,
      int limit,
      int offset) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    String sql =
        "SELECT s.id, s.series_key, min(o.time_period) AS first_period,"
            + " max(o.time_period) AS last_period, count(*) AS obs_count,"
            + " (array_agg(o.measure_values_json ORDER BY o.time_period DESC))[1] AS latest"
            + where(dataflowId, List.of(stage), keyFilters, null, null, keySearch, params)
            + " GROUP BY s.id, s.series_key ORDER BY s.series_key LIMIT :limit OFFSET :offset";
    params.addValue("limit", limit).addValue("offset", offset);
    return jdbc.query(
        sql,
        params,
        (rs, i) ->
            new SeriesSummary(
                rs.getLong("id"),
                rs.getString("series_key"),
                rs.getString("first_period"),
                rs.getString("last_period"),
                rs.getInt("obs_count"),
                rs.getString("latest")));
  }

  public long countSeries(
      Long dataflowId,
      ObservationStage stage,
      Map<Integer, List<String>> keyFilters,
      String keySearch) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    Long count =
        jdbc.queryForObject(
            "SELECT count(DISTINCT o.series_id)"
                + where(dataflowId, List.of(stage), keyFilters, null, null, keySearch, params),
            params,
            Long.class);
    return count == null ? 0 : count;
  }

  private String where(
      Long dataflowId,
      Collection<ObservationStage> stages,
      Map<Integer, List<String>> keyFilters,
      String fromPeriod,
      String toPeriod,
      String keySearch,
      MapSqlParameterSource params) {
    StringBuilder sql =
        new StringBuilder(
            " FROM observation o JOIN series s ON s.id = o.series_id"
                + " WHERE o.dataflow_id = :dataflowId AND o.valid_to IS NULL"
                + " AND o.stage IN (:stages)");
    params.addValue("dataflowId", dataflowId);
    params.addValue("stages", stages.stream().map(Enum::name).toList());
    if (fromPeriod != null && !fromPeriod.isBlank()) {
      sql.append(" AND o.time_period >= :fromPeriod");
      params.addValue("fromPeriod", fromPeriod.trim());
    }
    if (toPeriod != null && !toPeriod.isBlank()) {
      sql.append(" AND o.time_period <= :toPeriod");
      params.addValue("toPeriod", toPeriod.trim());
    }
    appendKeyConditions(sql, keyFilters, keySearch, params);
    return sql.toString();
  }

  /** Series-key substring search and per-position code filters on alias {@code s}. */
  private static void appendKeyConditions(
      StringBuilder sql,
      Map<Integer, List<String>> keyFilters,
      String keySearch,
      MapSqlParameterSource params) {
    if (keySearch != null && !keySearch.isBlank()) {
      sql.append(" AND s.series_key ILIKE :keySearch ESCAPE '\\'");
      params.addValue(
          "keySearch",
          "%"
              + keySearch.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
              + "%");
    }
    for (Integer position : new ArrayList<>(keyFilters.keySet())) {
      List<String> codes = keyFilters.get(position);
      if (codes == null || codes.isEmpty()) {
        continue;
      }
      // position is an Integer from our own DSD, never user text - safe to inline
      sql.append(" AND split_part(s.series_key, '.', ")
          .append(position.intValue())
          .append(") IN (:k")
          .append(position.intValue())
          .append(')');
      params.addValue("k" + position, codes);
    }
  }

  /**
   * Facet counts for one key dimension: code -> number of series with current observations in the
   * stage, applying all other selections (the caller leaves out the dimension's own selection so
   * its alternatives stay visible).
   */
  public Map<String, Long> facetCounts(
      Long dataflowId,
      ObservationStage stage,
      int position,
      Map<Integer, List<String>> keyFilters,
      String keySearch) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    StringBuilder sql =
        new StringBuilder("SELECT split_part(s.series_key, '.', ")
            .append(position)
            .append(
                ") AS code, count(*) AS n FROM series s WHERE s.dataflow_id = :dataflowId"
                    + " AND EXISTS (SELECT 1 FROM observation o WHERE o.dataflow_id = :dataflowId"
                    + " AND o.series_id = s.id AND o.stage = :stage AND o.valid_to IS NULL)");
    params.addValue("dataflowId", dataflowId).addValue("stage", stage.name());
    appendKeyConditions(sql, keyFilters, keySearch, params);
    sql.append(" GROUP BY 1");
    Map<String, Long> counts = new java.util.LinkedHashMap<>();
    jdbc.query(
        sql.toString(),
        params,
        rs -> {
          counts.put(rs.getString("code"), rs.getLong("n"));
        });
    return counts;
  }

  /**
   * Current Alpha observations that differ from the current Beta value (or have none): exactly what
   * {@code ObservationService.promoteToBeta} would publish - it compares the same JSON text.
   */
  public List<PendingChange> findPending(
      Long dataflowId,
      Map<Integer, List<String>> keyFilters,
      String keySearch,
      int limit,
      int offset) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    String sql =
        "SELECT o.series_id, s.series_key, o.time_period, o.measure_values_json AS alpha,"
            + " b.measure_values_json AS beta, o.valid_from, o.recorded_by, o.comment"
            + pendingFrom(dataflowId, keyFilters, keySearch, params)
            + " ORDER BY s.series_key, o.time_period LIMIT :limit OFFSET :offset";
    params.addValue("limit", limit).addValue("offset", offset);
    return jdbc.query(
        sql,
        params,
        (rs, i) ->
            new PendingChange(
                rs.getLong("series_id"),
                rs.getString("series_key"),
                rs.getString("time_period"),
                rs.getString("alpha"),
                rs.getString("beta"),
                rs.getTimestamp("valid_from").toInstant(),
                rs.getString("recorded_by"),
                rs.getString("comment")));
  }

  public long countPending(
      Long dataflowId, Map<Integer, List<String>> keyFilters, String keySearch) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    Long count =
        jdbc.queryForObject(
            "SELECT count(*)" + pendingFrom(dataflowId, keyFilters, keySearch, params),
            params,
            Long.class);
    return count == null ? 0 : count;
  }

  private static String pendingFrom(
      Long dataflowId,
      Map<Integer, List<String>> keyFilters,
      String keySearch,
      MapSqlParameterSource params) {
    StringBuilder sql =
        new StringBuilder(
            " FROM observation o JOIN series s ON s.id = o.series_id"
                + " LEFT JOIN observation b ON b.dataflow_id = o.dataflow_id"
                + " AND b.series_id = o.series_id AND b.time_period = o.time_period"
                + " AND b.stage = 'BETA' AND b.valid_to IS NULL"
                + " WHERE o.dataflow_id = :dataflowId AND o.stage = 'ALPHA' AND o.valid_to IS NULL"
                + " AND b.measure_values_json IS DISTINCT FROM o.measure_values_json");
    params.addValue("dataflowId", dataflowId);
    appendKeyConditions(sql, keyFilters, keySearch, params);
    return sql.toString();
  }

  /**
   * @param betaMeasureValuesJson null = new observation, not yet published
   */
  public record PendingChange(
      Long seriesId,
      String seriesKey,
      String timePeriod,
      String alphaMeasureValuesJson,
      String betaMeasureValuesJson,
      java.time.Instant validFrom,
      String recordedBy,
      String comment) {}

  public record SeriesSummary(
      Long seriesId,
      String seriesKey,
      String firstPeriod,
      String lastPeriod,
      int count,
      String latestMeasureValuesJson) {}

  public record ObservationListRow(
      Long id,
      Long seriesId,
      String seriesKey,
      String timePeriod,
      ObservationStage stage,
      String measureValuesJson,
      java.time.Instant validFrom,
      String recordedBy) {}
}
