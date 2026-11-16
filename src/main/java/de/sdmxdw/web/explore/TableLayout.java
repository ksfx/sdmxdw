package de.sdmxdw.web.explore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which dimensions of a cube go into the row sections, rows, columns and filters of the table view
 * (#15), following the annotation conventions of the .Stat Suite on the dataflow (or its DSD):
 *
 * <ul>
 *   <li>{@code LAYOUT_ROW_SECTION}, {@code LAYOUT_ROW}, {@code LAYOUT_COLUMN}: dimension ids,
 *       separated by commas
 *   <li>{@code DEFAULT}: {@code DIM=CODE+CODE,...}; {@code TIME_PERIOD_END} (or {@code
 *       TIME_PERIOD_START}) selects the period when time is a filter
 *   <li>{@code NOT_DISPLAYED}: {@code DIM} hides a filter, {@code DIM=CODE+CODE} hides codes
 *   <li>{@code LAYOUT_TIME_PERIOD_DESC}: latest periods first
 * </ul>
 *
 * A layout given in the URL (rows, columns, sections) replaces the annotated one. Without either, a
 * heuristic puts the dimension with the most codes into the rows, the second into the columns (time
 * if there is no second one) and the others into the filters.
 */
public record TableLayout(
    List<String> sections,
    List<String> rows,
    List<String> columns,
    List<String> filters,
    Set<String> hiddenFilters,
    Map<String, String> defaults,
    Map<String, Set<String>> hiddenCodes,
    boolean timeDescending,
    String defaultTime,
    Source source) {

  /** Where the arrangement of the dimensions comes from. */
  public enum Source {
    ANNOTATIONS,
    URL,
    HEURISTIC
  }

  /** Requested arrangement from the URL; all null = none. */
  public record Request(List<String> sections, List<String> rows, List<String> columns) {
    public boolean present() {
      return sections != null || rows != null || columns != null;
    }

    public static Request parse(String sections, String rows, String columns) {
      return new Request(ids(sections), ids(rows), ids(columns));
    }
  }

  /**
   * @param keyDimensions ids of the key dimensions in key order
   * @param timeDimension id of the time dimension, or null
   * @param annotations annotation type -> value (title, else text); the caller merges dataflow and
   *     DSD, the dataflow winning
   * @param codeCounts distinct codes with data per key dimension (heuristic)
   */
  public static TableLayout resolve(
      List<String> keyDimensions,
      String timeDimension,
      Map<String, String> annotations,
      Request request,
      Map<String, Integer> codeCounts) {
    List<String> all = new ArrayList<>(keyDimensions);
    if (timeDimension != null) {
      all.add(timeDimension);
    }
    Map<String, String> defaults = new LinkedHashMap<>();
    String defaultTime = null;
    for (Map.Entry<String, List<String>> e : assignments(annotations.get("DEFAULT")).entrySet()) {
      String dim = e.getKey().toUpperCase();
      if (e.getValue().isEmpty()) {
        continue;
      }
      if (dim.equals("TIME_PERIOD_END") || dim.equals("TIME_PERIOD_START") && defaultTime == null) {
        defaultTime = e.getValue().getFirst();
      } else if (all.contains(e.getKey())) {
        defaults.put(e.getKey(), e.getValue().getFirst());
      }
    }
    Set<String> hiddenFilters = new LinkedHashSet<>();
    Map<String, Set<String>> hiddenCodes = new HashMap<>();
    for (Map.Entry<String, List<String>> e :
        assignments(annotations.get("NOT_DISPLAYED")).entrySet()) {
      if (!all.contains(e.getKey())) {
        continue;
      }
      if (e.getValue().isEmpty()) {
        hiddenFilters.add(e.getKey());
      } else {
        hiddenCodes.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
      }
    }

    List<String> sections;
    List<String> rows;
    List<String> columns;
    Source source;
    if (request.present()) {
      sections = known(request.sections(), all);
      rows = known(request.rows(), all);
      columns = known(request.columns(), all);
      source = Source.URL;
    } else if (annotations.containsKey("LAYOUT_ROW") || annotations.containsKey("LAYOUT_COLUMN")) {
      sections = known(ids(annotations.get("LAYOUT_ROW_SECTION")), all);
      rows = known(ids(annotations.get("LAYOUT_ROW")), all);
      columns = known(ids(annotations.get("LAYOUT_COLUMN")), all);
      source = Source.ANNOTATIONS;
    } else {
      sections = List.of();
      List<String> candidates =
          keyDimensions.stream()
              .filter(d -> codeCounts.getOrDefault(d, 0) > 1 && !hiddenFilters.contains(d))
              .sorted(Comparator.comparing((String d) -> -codeCounts.getOrDefault(d, 0)))
              .toList();
      rows = candidates.isEmpty() ? List.of() : List.of(candidates.get(0));
      columns = candidates.size() > 1 ? List.of(candidates.get(1)) : List.of();
      source = Source.HEURISTIC;
    }
    // a dimension appears once; rows and columns are never both empty
    columns = without(columns, sections, rows);
    rows = without(rows, sections);
    if (rows.isEmpty() && columns.isEmpty()) {
      rows = timeDimension != null ? List.of(timeDimension) : keyDimensions.subList(0, 1);
    } else if (columns.isEmpty()
        && timeDimension != null
        && !rows.contains(timeDimension)
        && !sections.contains(timeDimension)) {
      columns = List.of(timeDimension);
    }
    List<String> onAxes = new ArrayList<>(sections);
    onAxes.addAll(rows);
    onAxes.addAll(columns);
    List<String> filters = all.stream().filter(d -> !onAxes.contains(d)).toList();
    return new TableLayout(
        sections,
        rows,
        columns,
        filters,
        hiddenFilters,
        defaults,
        hiddenCodes,
        annotations.containsKey("LAYOUT_TIME_PERIOD_DESC"),
        defaultTime,
        source);
  }

  /** "A, B ,C" -> [A, B, C]; null or blank -> null. */
  static List<String> ids(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  /** "FREQ=A+Q,REF_AREA" -> {FREQ=[A, Q], REF_AREA=[]}. */
  static Map<String, List<String>> assignments(String value) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    List<String> parts = ids(value);
    if (parts == null) {
      return result;
    }
    for (String part : parts) {
      int eq = part.indexOf('=');
      if (eq < 0) {
        result.put(part, List.of());
      } else {
        result.put(
            part.substring(0, eq).strip(),
            Arrays.stream(part.substring(eq + 1).split("\\+"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList());
      }
    }
    return result;
  }

  private static List<String> known(List<String> ids, List<String> all) {
    return ids == null ? List.of() : ids.stream().filter(all::contains).distinct().toList();
  }

  @SafeVarargs
  private static List<String> without(List<String> ids, List<String>... taken) {
    return ids.stream().filter(d -> Arrays.stream(taken).noneMatch(t -> t.contains(d))).toList();
  }
}
