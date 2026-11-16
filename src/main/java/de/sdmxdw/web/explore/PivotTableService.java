package de.sdmxdw.web.explore;

import de.sdmxdw.data.observation.ObservationQueryRepository.ObservationListRow;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.data.observation.ObservationStage;
import de.sdmxdw.structure.annotation.Annotation;
import de.sdmxdw.structure.annotation.AnnotationService;
import de.sdmxdw.structure.annotation.AnnotationTarget;
import de.sdmxdw.structure.codelist.Code;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
import de.sdmxdw.structure.dsd.Dimension;
import de.sdmxdw.structure.dsd.Measure;
import de.sdmxdw.web.ConceptCodes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/**
 * The table view of a time series cube (#15): one slice (filters fixed, one period unless time is
 * on an axis) laid out in row sections, rows and columns by {@link TableLayout}. Only the slice is
 * read from the database.
 */
@Component
public class PivotTableService {

  /** Cells read at most; larger slices are cut and the table says so. */
  static final int MAX_CELLS = 20_000;

  /** Periods shown at most when time is on an axis (the latest ones). */
  static final int MAX_PERIODS = 60;

  private static final Pattern INTEGER = Pattern.compile("-?\\d+");

  private final DataflowService dataflowService;
  private final DataStructureDefinitionService dsdService;
  private final CodelistService codelistService;
  private final ConceptSchemeService conceptSchemeService;
  private final ObservationService observationService;
  private final AnnotationService annotationService;

  public PivotTableService(
      DataflowService dataflowService,
      DataStructureDefinitionService dsdService,
      CodelistService codelistService,
      ConceptSchemeService conceptSchemeService,
      ObservationService observationService,
      AnnotationService annotationService) {
    this.dataflowService = dataflowService;
    this.dsdService = dsdService;
    this.codelistService = codelistService;
    this.conceptSchemeService = conceptSchemeService;
    this.observationService = observationService;
    this.annotationService = annotationService;
  }

  // --- result ----------------------------------------------------------------------------------

  public enum Role {
    SECTION,
    ROW,
    COLUMN,
    FILTER
  }

  public record Option(String code, String label, long count) {}

  /**
   * A dimension of the cube and its place in the table; filters carry their options and the
   * selected code (for time: the periods).
   */
  public record Dim(
      String id,
      String label,
      boolean time,
      Role role,
      boolean hidden,
      List<Option> options,
      String selected) {}

  /** A column header cell: code and name (time: the period, no name). */
  public record HeaderCell(String code, String name, int colspan) {}

  public record Cell(String value, Long seriesId) {}

  /** One row: code and name per row dimension, then the cells. */
  public record Row(List<String> codes, List<String> names, List<Cell> cells) {}

  public record Section(String label, List<Row> rows) {}

  public record MeasureOption(String code, String label) {}

  public record PivotTable(
      Dataflow dataflow,
      TableLayout layout,
      List<Dim> dimensions,
      List<String> rowHeaders,
      List<List<HeaderCell>> columnHeaders,
      List<Section> sections,
      int columnCount,
      List<MeasureOption> measures,
      String measure,
      int observations,
      boolean truncated,
      boolean periodsCut) {

    public boolean empty() {
      return observations == 0;
    }

    /** URL of this table (filters, layout, measure), for the way back from a series. */
    public String href(String stage) {
      String q = query();
      return "/explore/dataflows/"
          + dataflow.id()
          + "?view=table"
          + ("ALPHA".equals(stage) ? "&stage=ALPHA" : "")
          + (q.isEmpty() ? "" : "&" + q);
    }

    public int maxCells() {
      return MAX_CELLS;
    }

    public int maxPeriods() {
      return MAX_PERIODS;
    }

    /** Query string of the current table (filters, time, layout, measure) for links and export. */
    public String query() {
      List<String> parts = new ArrayList<>();
      for (Dim d : dimensions) {
        if (d.role() == Role.FILTER && d.selected() != null) {
          parts.add(
              (d.time() ? "time" : KEY_PARAM + d.id())
                  + "="
                  + java.net.URLEncoder.encode(
                      d.selected(), java.nio.charset.StandardCharsets.UTF_8));
        }
      }
      if (layout.source() == TableLayout.Source.URL) {
        parts.add("sections=" + String.join(",", layout.sections()));
        parts.add("rows=" + String.join(",", layout.rows()));
        parts.add("cols=" + String.join(",", layout.columns()));
      }
      if (measure != null && measures.size() > 1) {
        parts.add("measure=" + measure);
      }
      return String.join("&", parts);
    }
  }

  /** Filter parameter of a key dimension, shared with the other views of the dataflow page. */
  static final String KEY_PARAM = de.sdmxdw.web.KeyFilters.PARAM_PREFIX;

  // --- building --------------------------------------------------------------------------------

  public PivotTable build(
      Long dataflowId, ObservationStage stage, MultiValueMap<String, String> params) {
    Dataflow dataflow = dataflowService.findById(dataflowId);
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    Map<Long, String> conceptCodes = ConceptCodes.byConceptId(conceptSchemeService);
    Map<Long, String> conceptLabels = new HashMap<>();
    conceptSchemeService
        .findAll()
        .forEach(cs -> cs.concepts().forEach(c -> conceptLabels.put(c.id(), c.name())));
    List<Dimension> keyDims =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() != DataType.DATE)
            .sorted(Comparator.comparingInt(Dimension::position))
            .toList();
    Dimension timeDim =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() == DataType.DATE)
            .findFirst()
            .orElse(null);
    List<String> keyIds = keyDims.stream().map(d -> id(d, conceptCodes)).toList();
    String timeId = timeDim == null ? null : id(timeDim, conceptCodes);

    // codes per key dimension: names, order (ORDER annotation, else codelist order) and the codes'
    // own DEFAULT / NOT_DISPLAYED annotations
    Map<String, Map<String, String>> names = new HashMap<>();
    Map<String, Map<String, Integer>> order = new HashMap<>();
    Map<String, String> codeDefaults = new HashMap<>();
    Map<String, Set<String>> codeHidden = new HashMap<>();
    for (int i = 0; i < keyDims.size(); i++) {
      Dimension d = keyDims.get(i);
      String dim = keyIds.get(i);
      Map<String, String> n = new LinkedHashMap<>();
      Map<String, Integer> o = new HashMap<>();
      if (d.codelistId() != null) {
        Codelist cl = codelistService.findById(d.codelistId());
        List<Code> codes = cl.codes().stream().sorted(Comparator.comparing(Code::code)).toList();
        Map<Long, List<Annotation>> ann =
            annotationService.find(AnnotationTarget.CODE, codes.stream().map(Code::id).toList());
        for (int k = 0; k < codes.size(); k++) {
          Code c = codes.get(k);
          n.put(c.code(), c.name());
          int position = k;
          for (Annotation a : ann.getOrDefault(c.id(), List.of())) {
            String type = a.annotationType() == null ? "" : a.annotationType().toUpperCase();
            switch (type) {
              case "ORDER" -> {
                Matcher m = INTEGER.matcher(a.value());
                if (m.find()) {
                  position = Integer.parseInt(m.group()) - 100_000;
                }
              }
              case "DEFAULT" -> codeDefaults.putIfAbsent(dim, c.code());
              case "NOT_DISPLAYED" ->
                  codeHidden.computeIfAbsent(dim, x -> new LinkedHashSet<>()).add(c.code());
              default -> {}
            }
          }
          o.put(c.code(), position);
        }
      }
      names.put(dim, n);
      order.put(dim, o);
    }

    // DSD annotations, overridden by the dataflow's own
    Map<String, String> annotations = new HashMap<>();
    for (AnnotationTarget t : List.of(AnnotationTarget.DSD, AnnotationTarget.DATAFLOW)) {
      Long id = t == AnnotationTarget.DSD ? dsd.id() : dataflow.id();
      Map<String, String> level = new HashMap<>();
      for (Annotation a : annotationService.find(t, id)) {
        if (a.annotationType() != null) {
          level.putIfAbsent(a.annotationType().toUpperCase(), a.value());
        }
      }
      annotations.putAll(level);
    }

    TableLayout.Request request = request(params, keyIds, timeId);
    Map<String, Integer> codeCounts = new HashMap<>();
    boolean annotated =
        annotations.containsKey("LAYOUT_ROW") || annotations.containsKey("LAYOUT_COLUMN");
    if (!request.present() && !annotated) {
      for (int i = 0; i < keyIds.size(); i++) {
        codeCounts.put(
            keyIds.get(i),
            observationService.findKeyFacet(dataflowId, stage, i + 1, Map.of()).size());
      }
    }
    TableLayout layout = TableLayout.resolve(keyIds, timeId, annotations, request, codeCounts);
    Map<String, Set<String>> hidden = new HashMap<>(codeHidden);
    layout
        .hiddenCodes()
        .forEach((k, v) -> hidden.computeIfAbsent(k, x -> new LinkedHashSet<>()).addAll(v));

    // filters, one after the other: each offers the codes with data under the earlier selections
    Map<Integer, List<String>> keyFilters = new TreeMap<>();
    Map<String, Dim> filterDims = new HashMap<>();
    for (int i = 0; i < keyIds.size(); i++) {
      String dim = keyIds.get(i);
      if (!layout.filters().contains(dim)) {
        continue;
      }
      Map<String, Long> counts =
          observationService.findKeyFacet(dataflowId, stage, i + 1, keyFilters);
      Set<String> hiddenHere = hidden.getOrDefault(dim, Set.of());
      Map<String, Integer> o = order.get(dim);
      List<Option> options =
          counts.entrySet().stream()
              .filter(e -> !hiddenHere.contains(e.getKey()))
              .sorted(
                  Comparator.comparing(
                          (Map.Entry<String, Long> e) ->
                              o.getOrDefault(e.getKey(), Integer.MAX_VALUE))
                      .thenComparing(Map.Entry::getKey))
              .map(e -> new Option(e.getKey(), label(e.getKey(), names.get(dim)), e.getValue()))
              .toList();
      String selected =
          pick(
              options,
              params.getFirst(KEY_PARAM + dim),
              layout.defaults().get(dim),
              codeDefaults.get(dim));
      if (selected != null) {
        keyFilters.put(i + 1, List.of(selected));
      }
      filterDims.put(
          dim,
          new Dim(
              dim,
              label(keyDims.get(i), conceptLabels, dim),
              false,
              Role.FILTER,
              layout.hiddenFilters().contains(dim),
              options,
              selected));
    }

    // time: one period as filter (latest by default), or the periods on an axis
    String period = null;
    Dim timeFilter = null;
    if (timeId != null && layout.filters().contains(timeId)) {
      List<String> periods = observationService.findTimePeriods(dataflowId, stage, keyFilters);
      List<Option> options = periods.reversed().stream().map(p -> new Option(p, p, 0)).toList();
      period = pick(options, params.getFirst("time"), layout.defaultTime(), null);
      if (period == null && !periods.isEmpty()) {
        period = periods.getLast();
      }
      timeFilter =
          new Dim(
              timeId,
              label(timeDim, conceptLabels, timeId),
              true,
              Role.FILTER,
              layout.hiddenFilters().contains(timeId),
              options,
              period);
    }

    // the slice
    List<Measure> measures =
        dsd.measures().stream().sorted(Comparator.comparing(Measure::id)).toList();
    List<MeasureOption> measureOptions =
        measures.stream()
            .map(
                m ->
                    new MeasureOption(
                        conceptCodes.getOrDefault(m.conceptId(), "#" + m.conceptId()),
                        conceptLabels.getOrDefault(m.conceptId(), "")))
            .toList();
    String measureCode = params.getFirst("measure");
    Measure measure =
        measures.stream()
            .filter(m -> conceptCodes.getOrDefault(m.conceptId(), "").equals(measureCode))
            .findFirst()
            .orElse(measures.isEmpty() ? null : measures.getFirst());
    boolean timeFilterEmpty = timeFilter != null && period == null;
    List<ObservationListRow> slice =
        timeFilterEmpty || keyFilters.size() < filterDims.size()
            ? List.of()
            : observationService.findSlice(dataflowId, stage, keyFilters, period, MAX_CELLS + 1);
    boolean truncated = slice.size() > MAX_CELLS;
    if (truncated) {
      slice = slice.subList(0, MAX_CELLS);
    }

    // axes: the tuples with data, ordered by code order (time: by period)
    Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < keyIds.size(); i++) {
      index.put(keyIds.get(i), i);
    }
    Comparator<List<String>> sectionOrder = tupleOrder(layout.sections(), order, timeId, layout);
    Comparator<List<String>> rowOrder = tupleOrder(layout.rows(), order, timeId, layout);
    Comparator<List<String>> columnOrder = tupleOrder(layout.columns(), order, timeId, layout);
    Map<List<String>, Map<List<String>, Map<List<String>, Cell>>> cells =
        new TreeMap<>(sectionOrder);
    Set<List<String>> columnTuples = new java.util.TreeSet<>(columnOrder);
    Set<String> periodsOnAxis = new java.util.TreeSet<>();
    for (ObservationListRow r : slice) {
      String[] key = r.seriesKey().split("\\.", -1);
      Map<String, String> values = new HashMap<>();
      for (String dim : keyIds) {
        int i = index.get(dim);
        values.put(dim, i < key.length ? key[i] : "");
      }
      if (timeId != null) {
        values.put(timeId, r.timePeriod());
      }
      boolean skip = false;
      for (Map.Entry<String, Set<String>> h : hidden.entrySet()) {
        if (!layout.filters().contains(h.getKey())
            && h.getValue().contains(values.get(h.getKey()))) {
          skip = true;
        }
      }
      if (skip) {
        continue;
      }
      periodsOnAxis.add(r.timePeriod());
      String value =
          measure == null
              ? ""
              : observationService
                  .parseMeasureValues(r.measureValuesJson())
                  .getOrDefault(measure.id(), "");
      List<String> s = tuple(layout.sections(), values);
      List<String> row = tuple(layout.rows(), values);
      List<String> col = tuple(layout.columns(), values);
      columnTuples.add(col);
      cells
          .computeIfAbsent(s, k -> new TreeMap<>(rowOrder))
          .computeIfAbsent(row, k -> new HashMap<>())
          .put(col, new Cell(value, r.seriesId()));
    }

    // time on an axis: only the latest periods
    boolean periodsCut = false;
    boolean timeOnAxis =
        timeId != null
            && (layout.rows().contains(timeId)
                || layout.columns().contains(timeId)
                || layout.sections().contains(timeId));
    if (timeOnAxis && periodsOnAxis.size() > MAX_PERIODS) {
      Set<String> keep =
          new java.util.HashSet<>(
              new ArrayList<>(periodsOnAxis)
                  .subList(periodsOnAxis.size() - MAX_PERIODS, periodsOnAxis.size()));
      periodsCut = true;
      columnTuples.removeIf(t -> !keep(t, layout.columns(), timeId, keep));
      cells.keySet().removeIf(t -> !keep(t, layout.sections(), timeId, keep));
      cells
          .values()
          .forEach(rows -> rows.keySet().removeIf(t -> !keep(t, layout.rows(), timeId, keep)));
    }

    List<List<String>> columns = new ArrayList<>(columnTuples);
    List<Section> sections = new ArrayList<>();
    cells.forEach(
        (sectionTuple, rows) -> {
          List<Row> out = new ArrayList<>();
          rows.forEach(
              (rowTuple, byColumn) ->
                  out.add(
                      new Row(
                          rowTuple,
                          names(layout.rows(), rowTuple, names, timeId),
                          columns.stream()
                              .map(c -> byColumn.getOrDefault(c, new Cell("", null)))
                              .toList())));
          sections.add(
              new Section(
                  layout.sections().isEmpty()
                      ? null
                      : String.join(" · ", titles(layout.sections(), sectionTuple, names, timeId)),
                  out));
        });

    List<List<HeaderCell>> header = new ArrayList<>();
    for (int level = 0; level < layout.columns().size(); level++) {
      List<HeaderCell> cellsOfLevel = new ArrayList<>();
      int i = 0;
      while (i < columns.size()) {
        int j = i + 1;
        // merge neighbours that agree on this and all higher levels
        while (j < columns.size()
            && columns.get(j).subList(0, level + 1).equals(columns.get(i).subList(0, level + 1))) {
          j++;
        }
        String dim = layout.columns().get(level);
        String code = columns.get(i).get(level);
        cellsOfLevel.add(
            new HeaderCell(
                code,
                dim.equals(timeId) ? null : names.getOrDefault(dim, Map.of()).get(code),
                j - i));
        i = j;
      }
      header.add(cellsOfLevel);
    }

    List<Dim> dims = new ArrayList<>();
    for (int i = 0; i < keyIds.size(); i++) {
      String dim = keyIds.get(i);
      dims.add(
          filterDims.containsKey(dim)
              ? filterDims.get(dim)
              : new Dim(
                  dim,
                  label(keyDims.get(i), conceptLabels, dim),
                  false,
                  role(layout, dim),
                  false,
                  List.of(),
                  null));
    }
    if (timeId != null) {
      dims.add(
          timeFilter != null
              ? timeFilter
              : new Dim(
                  timeId,
                  label(timeDim, conceptLabels, timeId),
                  true,
                  role(layout, timeId),
                  false,
                  List.of(),
                  null));
    }
    List<String> rowHeaders =
        layout.rows().stream()
            .map(d -> dims.stream().filter(x -> x.id().equals(d)).findFirst().orElseThrow().label())
            .toList();
    int observations =
        (int)
            sections.stream()
                .flatMap(x -> x.rows().stream())
                .flatMap(x -> x.cells().stream())
                .filter(c -> c.seriesId() != null)
                .count();
    return new PivotTable(
        dataflow,
        layout,
        dims,
        rowHeaders,
        header,
        sections,
        columns.size(),
        measureOptions,
        measure == null ? null : conceptCodes.getOrDefault(measure.conceptId(), null),
        observations,
        truncated,
        periodsCut);
  }

  // --- helpers ---------------------------------------------------------------------------------

  /**
   * The layout asked for in the URL: {@code sections}, {@code rows}, {@code cols} (dimension ids),
   * or the role selects of the page ({@code role_<DIM>=SECTION|ROW|COLUMN|FILTER}).
   */
  static TableLayout.Request request(
      MultiValueMap<String, String> params, List<String> keyIds, String timeId) {
    List<String> all = new ArrayList<>(keyIds);
    if (timeId != null) {
      all.add(timeId);
    }
    if (all.stream().anyMatch(d -> params.containsKey("role_" + d))) {
      List<String> sections = new ArrayList<>();
      List<String> rows = new ArrayList<>();
      List<String> columns = new ArrayList<>();
      for (String d : all) {
        switch (String.valueOf(params.getFirst("role_" + d))) {
          case "SECTION" -> sections.add(d);
          case "ROW" -> rows.add(d);
          case "COLUMN" -> columns.add(d);
          default -> {}
        }
      }
      return new TableLayout.Request(sections, rows, columns);
    }
    return TableLayout.Request.parse(
        params.getFirst("sections"), params.getFirst("rows"), params.getFirst("cols"));
  }

  /** The requested code if it has data, else the annotated default, else the most frequent one. */
  private static String pick(List<Option> options, String... preferred) {
    for (String p : preferred) {
      if (p != null && options.stream().anyMatch(o -> o.code().equals(p))) {
        return p;
      }
    }
    return options.stream()
        .max(Comparator.comparingLong(Option::count))
        .map(Option::code)
        .orElse(null);
  }

  private static Comparator<List<String>> tupleOrder(
      List<String> dims,
      Map<String, Map<String, Integer>> order,
      String timeId,
      TableLayout layout) {
    return (a, b) -> {
      for (int i = 0; i < dims.size(); i++) {
        String dim = dims.get(i);
        int c;
        if (dim.equals(timeId)) {
          c = layout.timeDescending() ? b.get(i).compareTo(a.get(i)) : a.get(i).compareTo(b.get(i));
        } else {
          Map<String, Integer> o = order.getOrDefault(dim, Map.of());
          c =
              Comparator.comparing((String x) -> o.getOrDefault(x, Integer.MAX_VALUE))
                  .thenComparing(x -> x)
                  .compare(a.get(i), b.get(i));
        }
        if (c != 0) {
          return c;
        }
      }
      return 0;
    };
  }

  private static List<String> tuple(List<String> dims, Map<String, String> values) {
    return dims.stream().map(values::get).toList();
  }

  private static boolean keep(
      List<String> tuple, List<String> dims, String timeId, Set<String> keep) {
    int i = dims.indexOf(timeId);
    return i < 0 || keep.contains(tuple.get(i));
  }

  /** The names of a tuple's codes (null for time and codes without name). */
  private static List<String> names(
      List<String> dims,
      List<String> tuple,
      Map<String, Map<String, String>> names,
      String timeId) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < dims.size(); i++) {
      out.add(
          dims.get(i).equals(timeId)
              ? null
              : names.getOrDefault(dims.get(i), Map.of()).get(tuple.get(i)));
    }
    return out;
  }

  /** "code - name" per code of a tuple. */
  private static List<String> titles(
      List<String> dims,
      List<String> tuple,
      Map<String, Map<String, String>> names,
      String timeId) {
    List<String> n = names(dims, tuple, names, timeId);
    List<String> out = new ArrayList<>();
    for (int i = 0; i < tuple.size(); i++) {
      out.add(n.get(i) == null ? tuple.get(i) : tuple.get(i) + " - " + n.get(i));
    }
    return out;
  }

  private static String label(String code, Map<String, String> names) {
    String name = names == null ? null : names.get(code);
    return name == null ? code : code + " - " + name;
  }

  private static String label(Dimension d, Map<Long, String> conceptLabels, String fallback) {
    String l = conceptLabels.get(d.conceptId());
    return l == null || l.isBlank() ? fallback : l;
  }

  private static String id(Dimension d, Map<Long, String> conceptCodes) {
    return conceptCodes.getOrDefault(d.conceptId(), "#" + d.conceptId());
  }

  private static Role role(TableLayout layout, String dim) {
    if (layout.sections().contains(dim)) {
      return Role.SECTION;
    }
    if (layout.rows().contains(dim)) {
      return Role.ROW;
    }
    return layout.columns().contains(dim) ? Role.COLUMN : Role.FILTER;
  }
}
