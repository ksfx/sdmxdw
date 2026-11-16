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
      String valuePeriod,
      int limit,
      int offset) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    // the latest values, or those of one period (a cross-section; null if the series lacks it)
    String values =
        valuePeriod == null
            ? " (array_agg(o.measure_values_json ORDER BY o.time_period DESC))[1] AS latest"
            : " (array_agg(o.measure_values_json) FILTER (WHERE o.time_period = :valuePeriod))[1]"
                + " AS latest";
    if (valuePeriod != null) {
      params.addValue("valuePeriod", valuePeriod);
    }
    String sql =
        "SELECT s.id, s.series_key, min(o.time_period) AS first_period,"
            + " max(o.time_period) AS last_period, count(*) AS obs_count,"
            + values
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

  // --- one series (#27: long series page by page) ------------------------------------------------

  /**
   * A period of one series: its values, and for Alpha whether it is deleted but still published.
   */
  public record SeriesValue(String timePeriod, String measureValuesJson, boolean deleted) {}

  private static String seriesRows(ObservationStage stage) {
    String current =
        "SELECT time_period, measure_values_json, false AS deleted FROM observation"
            + " WHERE dataflow_id = :df AND series_id = :sid AND stage = :stage AND valid_to IS NULL";
    return stage == ObservationStage.BETA
        ? current
        : current
            // deleted in Alpha, still published (#25)
            + " UNION ALL SELECT b.time_period, b.measure_values_json, true FROM observation b"
            + " WHERE b.dataflow_id = :df AND b.series_id = :sid AND b.stage = 'BETA'"
            + " AND b.valid_to IS NULL AND NOT EXISTS (SELECT 1 FROM observation a"
            + " WHERE a.dataflow_id = :df AND a.series_id = :sid AND a.time_period = b.time_period"
            + " AND a.stage = 'ALPHA' AND a.valid_to IS NULL)";
  }

  /** One page of a series' current periods, latest first. */
  public List<SeriesValue> seriesPage(
      Long dataflowId, Long seriesId, ObservationStage stage, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM ("
            + seriesRows(stage)
            + ") r ORDER BY time_period DESC LIMIT :limit OFFSET :offset",
        new MapSqlParameterSource("df", dataflowId)
            .addValue("sid", seriesId)
            .addValue("stage", stage.name())
            .addValue("limit", limit)
            .addValue("offset", offset),
        (rs, i) ->
            new SeriesValue(
                rs.getString("time_period"),
                rs.getString("measure_values_json"),
                rs.getBoolean("deleted")));
  }

  /** Number of periods {@link #seriesPage} pages through. */
  public int countSeriesPeriods(Long dataflowId, Long seriesId, ObservationStage stage) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM (" + seriesRows(stage) + ") r",
            new MapSqlParameterSource("df", dataflowId)
                .addValue("sid", seriesId)
                .addValue("stage", stage.name()),
            Integer.class);
    return n == null ? 0 : n;
  }

  /** The values of some periods, current or as of an instant: period -> measure values JSON. */
  public Map<String, String> seriesValuesAt(
      Long dataflowId,
      Long seriesId,
      ObservationStage stage,
      Collection<String> periods,
      java.time.Instant asOf) {
    Map<String, String> result = new java.util.HashMap<>();
    if (periods.isEmpty()) {
      return result;
    }
    MapSqlParameterSource p =
        new MapSqlParameterSource("df", dataflowId)
            .addValue("sid", seriesId)
            .addValue("stage", stage.name())
            .addValue("periods", periods);
    String validity = " AND valid_to IS NULL";
    if (asOf != null) {
      validity = " AND valid_from <= :asOf AND (valid_to IS NULL OR valid_to > :asOf)";
      p.addValue("asOf", java.sql.Timestamp.from(asOf));
    }
    jdbc.query(
        "SELECT time_period, measure_values_json FROM observation WHERE dataflow_id = :df"
            + " AND series_id = :sid AND stage = :stage AND time_period IN (:periods)"
            + validity,
        p,
        rs -> {
          result.put(rs.getString(1), rs.getString(2));
        });
    return result;
  }

  /**
   * Distinct successive values per period (1 = never revised); identical successive vintages from
   * unchanged re-deliveries count once. {@code periods} null = all periods.
   */
  public Map<String, Integer> revisionCounts(
      Long dataflowId, Long seriesId, ObservationStage stage, Collection<String> periods) {
    MapSqlParameterSource p =
        new MapSqlParameterSource("df", dataflowId)
            .addValue("sid", seriesId)
            .addValue("stage", stage.name());
    String only = "";
    if (periods != null) {
      if (periods.isEmpty()) {
        return Map.of();
      }
      only = " AND time_period IN (:periods)";
      p.addValue("periods", periods);
    }
    Map<String, Integer> result = new java.util.HashMap<>();
    jdbc.query(
        "SELECT time_period, count(*) FILTER (WHERE prev IS DISTINCT FROM v) FROM ("
            + "SELECT time_period, measure_values_json AS v, lag(measure_values_json)"
            + " OVER (PARTITION BY time_period ORDER BY valid_from) AS prev FROM observation"
            + " WHERE dataflow_id = :df AND series_id = :sid AND stage = :stage"
            + only
            + ") x GROUP BY 1",
        p,
        rs -> {
          result.put(rs.getString(1), rs.getInt(2));
        });
    return result;
  }

  /** Periods of one series a promotion would change (new, changed or deleted in Alpha). */
  public int countSeriesPending(Long dataflowId, Long seriesId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM ("
                + "SELECT a.time_period FROM observation a LEFT JOIN observation b"
                + " ON b.dataflow_id = :df AND b.series_id = :sid AND b.time_period = a.time_period"
                + " AND b.stage = 'BETA' AND b.valid_to IS NULL"
                + " WHERE a.dataflow_id = :df AND a.series_id = :sid AND a.stage = 'ALPHA'"
                + " AND a.valid_to IS NULL"
                + " AND b.measure_values_json IS DISTINCT FROM a.measure_values_json"
                + " UNION ALL SELECT b.time_period FROM observation b"
                + " WHERE b.dataflow_id = :df AND b.series_id = :sid AND b.stage = 'BETA'"
                + " AND b.valid_to IS NULL AND NOT EXISTS (SELECT 1 FROM observation a"
                + " WHERE a.dataflow_id = :df AND a.series_id = :sid"
                + " AND a.time_period = b.time_period AND a.stage = 'ALPHA' AND a.valid_to IS NULL)"
                + ") x",
            new MapSqlParameterSource("df", dataflowId).addValue("sid", seriesId),
            Integer.class);
    return n == null ? 0 : n;
  }

  /**
   * One measure of a series for the chart, ascending: [period, value] - current or as of an
   * instant, read out of the JSON in SQL.
   */
  public List<String[]> seriesChart(
      Long dataflowId,
      Long seriesId,
      ObservationStage stage,
      Long measureId,
      java.time.Instant asOf) {
    MapSqlParameterSource p =
        new MapSqlParameterSource("df", dataflowId)
            .addValue("sid", seriesId)
            .addValue("stage", stage.name())
            .addValue("m", String.valueOf(measureId));
    String validity = " AND valid_to IS NULL";
    if (asOf != null) {
      validity = " AND valid_from <= :asOf AND (valid_to IS NULL OR valid_to > :asOf)";
      p.addValue("asOf", java.sql.Timestamp.from(asOf));
    }
    return jdbc.query(
        "SELECT time_period, measure_values_json::jsonb ->> :m FROM observation"
            + " WHERE dataflow_id = :df AND series_id = :sid AND stage = :stage"
            + validity
            + " ORDER BY time_period",
        p,
        (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
  }

  /** Time periods with current observations in the stage under the key filters, ascending. */
  public List<String> timePeriods(
      Long dataflowId, ObservationStage stage, Map<Integer, List<String>> keyFilters) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    return jdbc.queryForList(
        "SELECT DISTINCT o.time_period"
            + where(dataflowId, List.of(stage), keyFilters, null, null, null, params)
            + " ORDER BY 1",
        params,
        String.class);
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
   * What {@code ObservationService.promoteToBeta} would publish - it compares the same JSON text:
   * current Alpha observations that differ from the current Beta value (or have none), and current
   * Beta observations without a current Alpha one, i.e. deleted in Alpha (#25).
   */
  public List<PendingChange> findPending(
      Long dataflowId,
      Map<Integer, List<String>> keyFilters,
      String keySearch,
      int limit,
      int offset) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    String sql =
        "SELECT pc.*, s.series_key"
            + pendingFrom(dataflowId, keyFilters, keySearch, params)
            + " ORDER BY s.series_key, pc.time_period LIMIT :limit OFFSET :offset";
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
                rs.getTimestamp("changed_at").toInstant(),
                rs.getString("changed_by"),
                rs.getString("reason")));
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
    String alphaCurrent =
        " a.dataflow_id = :dataflowId AND a.series_id = b.series_id"
            + " AND a.time_period = b.time_period AND a.stage = 'ALPHA' AND a.valid_to IS NULL";
    StringBuilder sql =
        new StringBuilder(
            " FROM ("
                // new or changed in Alpha
                + "SELECT o.series_id, o.time_period, o.measure_values_json AS alpha,"
                + " b.measure_values_json AS beta, o.valid_from AS changed_at,"
                + " o.recorded_by AS changed_by, o.comment AS reason"
                + " FROM observation o LEFT JOIN observation b ON b.dataflow_id = o.dataflow_id"
                + " AND b.series_id = o.series_id AND b.time_period = o.time_period"
                + " AND b.stage = 'BETA' AND b.valid_to IS NULL"
                + " WHERE o.dataflow_id = :dataflowId AND o.stage = 'ALPHA' AND o.valid_to IS NULL"
                + " AND b.measure_values_json IS DISTINCT FROM o.measure_values_json"
                // deleted in Alpha, still published: who deleted it and why from the last vintage
                + " UNION ALL SELECT b.series_id, b.time_period, NULL, b.measure_values_json,"
                + " d.valid_to, d.deleted_by, d.delete_reason FROM observation b"
                + " LEFT JOIN LATERAL (SELECT a.valid_to, a.deleted_by, a.delete_reason"
                + " FROM observation a WHERE a.dataflow_id = :dataflowId"
                + " AND a.series_id = b.series_id AND a.time_period = b.time_period"
                + " AND a.stage = 'ALPHA' ORDER BY a.valid_to DESC NULLS FIRST LIMIT 1) d ON true"
                + " WHERE b.dataflow_id = :dataflowId AND b.stage = 'BETA' AND b.valid_to IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM observation a WHERE"
                + alphaCurrent
                + ")) pc JOIN series s ON s.id = pc.series_id WHERE true");
    params.addValue("dataflowId", dataflowId);
    appendKeyConditions(sql, keyFilters, keySearch, params);
    return sql.toString();
  }

  /**
   * @param alphaMeasureValuesJson null = deleted in Alpha (#25)
   * @param betaMeasureValuesJson null = new observation, not yet published
   * @param validFrom when it was changed or deleted
   * @param recordedBy who changed or deleted it
   * @param comment reason of a correction or deletion
   */
  public record PendingChange(
      Long seriesId,
      String seriesKey,
      String timePeriod,
      String alphaMeasureValuesJson,
      String betaMeasureValuesJson,
      java.time.Instant validFrom,
      String recordedBy,
      String comment) {

    public boolean deleted() {
      return alphaMeasureValuesJson == null;
    }
  }

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
