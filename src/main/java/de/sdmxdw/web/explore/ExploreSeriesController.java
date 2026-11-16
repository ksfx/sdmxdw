package de.sdmxdw.web.explore;

import de.sdmxdw.data.observation.Observation;
import de.sdmxdw.data.observation.ObservationQueryRepository.SeriesValue;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.data.observation.ObservationStage;
import de.sdmxdw.data.series.Series;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.Measure;
import de.sdmxdw.web.AsOf;
import de.sdmxdw.web.KeyFilters;
import de.sdmxdw.web.Roles;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * One series in the explorer: the series page, its preview in the explorer panel, the value table
 * and its inline editing (#16, #25). Long series (#27, e.g. daily share prices since 1999) stay
 * fast: the value table is paged (latest periods first), revisions load when opened, editing swaps
 * single rows, and the chart reads one measure as JSON.
 */
@Controller
@RequestMapping("/explore/dataflows/{dataflowId}/series/{seriesId}")
public class ExploreSeriesController {

  /** Periods per page of the value table. */
  static final int PAGE_SIZE = 100;

  private final DataflowService dataflowService;
  private final DataStructureDefinitionService dsdService;
  private final ObservationService observationService;
  private final SeriesStructure structure;
  private final Breadcrumbs breadcrumbs;

  public ExploreSeriesController(
      DataflowService dataflowService,
      DataStructureDefinitionService dsdService,
      ObservationService observationService,
      SeriesStructure structure,
      Breadcrumbs breadcrumbs) {
    this.dataflowService = dataflowService;
    this.dsdService = dsdService;
    this.observationService = observationService;
    this.structure = structure;
    this.breadcrumbs = breadcrumbs;
  }

  // --- pages -----------------------------------------------------------------------------------

  @GetMapping
  public String page(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String ret,
      Model model) {
    View v = view(dataflowId, seriesId, stage, asOf, false, model);
    header(v, model);
    table(v, 0, model);
    String dataflowHref = "/explore/dataflows/" + dataflowId + (v.alpha() ? "?stage=ALPHA" : "");
    List<Breadcrumbs.Crumb> crumbs = new ArrayList<>(breadcrumbs.forDataflow(dataflowId));
    crumbs.add(new Breadcrumbs.Crumb(v.dataflow().artefactId(), v.dataflow().name(), dataflowHref));
    // opened from a table: back to that table with its filters
    String table = tableReturn(dataflowId, ret);
    if (table != null) {
      crumbs.add(new Breadcrumbs.Crumb(tableLabel(table), "Back to the table", table));
    }
    crumbs.add(new Breadcrumbs.Crumb(v.series().seriesKey(), null, null));
    model.addAttribute("crumbs", crumbs);
    model.addAttribute("backHref", table != null ? table : dataflowHref);
    model.addAttribute("ret", table);
    return "explore/series";
  }

  /** The series in the explorer panel next to the tree. */
  @GetMapping("/panel")
  public String panel(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String stage,
      Model model) {
    View v = view(dataflowId, seriesId, stage, asOf, true, model);
    header(v, model);
    table(v, 0, model);
    return "explore/fragments/series-panel";
  }

  /** Values of the chart: one measure (the first), and the published-as-of values if asked. */
  @GetMapping(value = "/chart.json", produces = "application/json")
  @ResponseBody
  public Map<String, Object> chart(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String stage) {
    ObservationStage s = ExploreDataflowController.parseStage(stage, Roles.isDatenmanager());
    DataStructureDefinition dsd = dsd(dataflowId);
    Long measureId = measures(dsd).stream().map(Measure::id).findFirst().orElse(null);
    Map<String, Object> result = new LinkedHashMap<>();
    if (measureId == null) {
      result.put("labels", List.of());
      result.put("values", List.of());
      return result;
    }
    List<String[]> current =
        observationService.findSeriesChart(dataflowId, seriesId, s, measureId, null);
    result.put("labels", current.stream().map(r -> r[0]).toList());
    result.put("values", current.stream().map(r -> number(r[1])).toList());
    Instant at = AsOf.parse(asOf);
    if (at != null) {
      Map<String, Double> then = new HashMap<>();
      observationService
          .findSeriesChart(dataflowId, seriesId, s, measureId, at)
          .forEach(r -> then.put(r[0], number(r[1])));
      result.put("asOf", current.stream().map(r -> then.get(r[0])).toList());
      result.put("asOfLabel", "as of " + asOf.strip());
    }
    return result;
  }

  // --- value table (htmx) ----------------------------------------------------------------------

  /** One page of the value table; also "Cancel" of the add form. */
  @GetMapping("/value-table")
  public String valueTable(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String stage,
      @RequestParam(defaultValue = "false") boolean panelMode,
      @RequestParam(defaultValue = "0") int vpage,
      Model model) {
    View v = view(dataflowId, seriesId, stage, asOf, panelMode, model);
    table(v, vpage, model);
    return "explore/fragments/series-parts :: valueTable";
  }

  /** The revision history of one period, loaded when its "revised" badge is opened. */
  @GetMapping("/revisions")
  public String revisions(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam String timePeriod,
      @RequestParam(required = false) String stage,
      Model model) {
    ObservationStage s = ExploreDataflowController.parseStage(stage, Roles.isDatenmanager());
    List<Measure> measures = measures(dsd(dataflowId));
    Map<Long, SeriesStructure.ConceptRef> concepts = structure.conceptRefs();
    List<Revision> list = new ArrayList<>();
    Map<Long, String> last = null;
    for (Observation o : observationService.findHistory(seriesId, timePeriod, s)) {
      Map<Long, String> values = observationService.parseMeasureValues(o.measureValuesJson());
      String validTo = o.validTo() == null ? null : o.validTo().toString();
      if (values.equals(last) && !list.isEmpty()) {
        // an unchanged re-delivery is no revision: extend the previous entry
        Revision previous = list.removeLast();
        list.add(new Revision(previous.values(), previous.validFrom(), validTo, o.comment()));
      } else {
        list.add(
            new Revision(
                label(values, measures, concepts), o.validFrom().toString(), validTo, o.comment()));
      }
      last = values;
    }
    model.addAttribute("revisions", list);
    return "explore/fragments/series-parts :: revisionList";
  }

  /** One row as shown; "Cancel" of the row editors. */
  @GetMapping("/row")
  public String row(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam String timePeriod,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String stage,
      @RequestParam(defaultValue = "false") boolean panelMode,
      Model model) {
    View v = view(dataflowId, seriesId, stage, asOf, panelMode, model);
    return rowOrGone(v, timePeriod, null, model);
  }

  /** Inline editor for one period (Datenmanager, Alpha view). */
  @GetMapping("/edit-value")
  public String editValue(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam String timePeriod,
      @RequestParam(required = false) String asOf,
      @RequestParam(defaultValue = "false") boolean panelMode,
      Model model) {
    View v = view(dataflowId, seriesId, ObservationStage.ALPHA.name(), asOf, panelMode, model);
    // a period deleted in Alpha but still published is restored from its published value
    Observation current =
        observationService
            .findCurrentOne(dataflowId, seriesId, timePeriod, ObservationStage.ALPHA)
            .or(
                () ->
                    observationService.findCurrentOne(
                        dataflowId, seriesId, timePeriod, ObservationStage.BETA))
            .orElseThrow();
    Map<Long, String> values = observationService.parseMeasureValues(current.measureValuesJson());
    model.addAttribute("row", rowsFor(v, List.of(timePeriod)).getFirst());
    model.addAttribute(
        "editValues", v.measures().stream().map(m -> values.getOrDefault(m.id(), "")).toList());
    return "explore/fragments/series-parts :: editRow";
  }

  @PostMapping("/correct")
  public String correctValue(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam String timePeriod,
      @RequestParam(required = false) String comment,
      @RequestParam(required = false) String asOf,
      @RequestParam(defaultValue = "false") boolean panelMode,
      @RequestParam Map<String, String> allParams,
      Authentication authentication,
      Model model) {
    observationService.correctObservation(
        dataflowId,
        seriesId,
        timePeriod,
        measureValues(allParams),
        comment,
        authentication.getName());
    View v = view(dataflowId, seriesId, ObservationStage.ALPHA.name(), asOf, panelMode, model);
    model.addAttribute("savedPeriod", timePeriod);
    return rowOrGone(v, timePeriod, null, model);
  }

  /** Inline form to add a period (Datenmanager, Alpha view; #25), on the first page. */
  @GetMapping("/add-value")
  public String addValueForm(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      @RequestParam(defaultValue = "false") boolean panelMode,
      Model model) {
    View v = view(dataflowId, seriesId, ObservationStage.ALPHA.name(), asOf, panelMode, model);
    table(v, 0, model);
    model.addAttribute("addRow", true);
    return "explore/fragments/series-parts :: valueTable";
  }

  @PostMapping("/add")
  public String addValue(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam String timePeriod,
      @RequestParam(required = false) String comment,
      @RequestParam(required = false) String asOf,
      @RequestParam(defaultValue = "false") boolean panelMode,
      @RequestParam Map<String, String> allParams,
      Authentication authentication,
      Model model) {
    Map<Long, String> measureValues = measureValues(allParams);
    String error = null;
    try {
      observationService.addObservation(
          dataflowId, seriesId, timePeriod, measureValues, comment, authentication.getName());
    } catch (IllegalArgumentException e) {
      error = e.getMessage();
    }
    View v = view(dataflowId, seriesId, ObservationStage.ALPHA.name(), asOf, panelMode, model);
    table(v, 0, model);
    if (error != null) {
      // the form again with what was entered
      model.addAttribute("addRow", true);
      model.addAttribute("addPeriod", timePeriod);
      model.addAttribute(
          "addValues",
          v.measures().stream().map(m -> measureValues.getOrDefault(m.id(), "")).toList());
      model.addAttribute("addComment", comment);
      model.addAttribute("tableError", error);
    } else {
      model.addAttribute("savedPeriod", timePeriod.strip());
      model.addAttribute("tableMessage", timePeriod.strip() + " added.");
    }
    return "explore/fragments/series-parts :: valueTable";
  }

  /** Inline confirmation to delete one period, with a reason (Datenmanager, Alpha view; #25). */
  @GetMapping("/delete-value")
  public String deleteValueForm(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam String timePeriod,
      @RequestParam(required = false) String asOf,
      @RequestParam(defaultValue = "false") boolean panelMode,
      Model model) {
    View v = view(dataflowId, seriesId, ObservationStage.ALPHA.name(), asOf, panelMode, model);
    model.addAttribute("row", rowsFor(v, List.of(timePeriod)).getFirst());
    return "explore/fragments/series-parts :: deleteRow";
  }

  @PostMapping("/delete")
  public String deleteValue(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam String timePeriod,
      @RequestParam(required = false) String reason,
      @RequestParam(required = false) String asOf,
      @RequestParam(defaultValue = "false") boolean panelMode,
      Authentication authentication,
      Model model) {
    View v = view(dataflowId, seriesId, ObservationStage.ALPHA.name(), asOf, panelMode, model);
    try {
      observationService.deleteObservation(
          dataflowId, seriesId, timePeriod, reason, authentication.getName());
    } catch (IllegalArgumentException e) {
      model.addAttribute("row", rowsFor(v, List.of(timePeriod)).getFirst());
      model.addAttribute("rowError", e.getMessage());
      return "explore/fragments/series-parts :: deleteRow";
    }
    // still published: the row stays, struck through; never published: it is gone
    return rowOrGone(v, timePeriod, timePeriod + " deleted.", model);
  }

  // --- model -----------------------------------------------------------------------------------

  /** What every part of the series view needs. */
  record View(
      Dataflow dataflow,
      DataStructureDefinition dsd,
      Series series,
      List<Measure> measures,
      ObservationStage stage,
      String asOf,
      Instant asOfInstant,
      boolean datenmanager,
      boolean panelMode) {
    boolean alpha() {
      return stage == ObservationStage.ALPHA;
    }
  }

  private View view(
      Long dataflowId,
      Long seriesId,
      String stageParam,
      String asOf,
      boolean panelMode,
      Model model) {
    boolean dm = Roles.isDatenmanager();
    ObservationStage stage = ExploreDataflowController.parseStage(stageParam, dm);
    Dataflow dataflow = dataflowService.findById(dataflowId);
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    Series series = observationService.findSeriesById(seriesId);
    if (!series.dataflowId().equals(dataflowId)) {
      throw new IllegalArgumentException("Series " + seriesId + " is not part of " + dataflowId);
    }
    String cleanAsOf = asOf == null || asOf.isBlank() ? null : asOf.strip();
    View v =
        new View(
            dataflow,
            dsd,
            series,
            measures(dsd),
            stage,
            cleanAsOf,
            AsOf.parse(cleanAsOf),
            dm,
            panelMode);
    Map<Long, SeriesStructure.ConceptRef> concepts = structure.conceptRefs();
    model.addAttribute("dataflow", dataflow);
    model.addAttribute("dsd", dsd);
    model.addAttribute("seriesId", seriesId);
    model.addAttribute("seriesKey", series.seriesKey());
    model.addAttribute("stage", stage.name());
    model.addAttribute("isDatenmanager", dm);
    model.addAttribute("canEdit", dm && stage == ObservationStage.ALPHA);
    model.addAttribute("panelMode", panelMode);
    model.addAttribute("asOf", cleanAsOf);
    model.addAttribute("asOfInstant", v.asOfInstant() == null ? null : v.asOfInstant().toString());
    model.addAttribute("measureIds", v.measures().stream().map(Measure::id).toList());
    model.addAttribute(
        "measureColumns",
        v.measures().stream()
            .map(
                m -> {
                  SeriesStructure.ConceptRef c = concepts.get(m.conceptId());
                  return new MeasureColumn(
                      v.measures().size() == 1 ? "Value" : c == null ? "#" : c.code(),
                      c == null ? "" : c.label());
                })
            .toList());
    model.addAttribute("addRow", false);
    return v;
  }

  /** Meta data, revision and pending counts of the page head. */
  private void header(View v, Model model) {
    model.addAttribute(
        "keyDimensions",
        structure.keyDimensionInfos(v.dsd(), v.series().seriesKey(), structure.conceptRefs()));
    model.addAttribute(
        "revisedCount",
        observationService
            .findRevisionCounts(v.dataflow().id(), v.series().id(), v.stage(), null)
            .values()
            .stream()
            .filter(n -> n > 1)
            .count());
    model.addAttribute(
        "pendingCount",
        v.alpha() ? observationService.countSeriesPending(v.dataflow().id(), v.series().id()) : 0);
  }

  /** One page of rows, latest period first, and the pager. */
  private void table(View v, int vpage, Model model) {
    int total =
        observationService.countSeriesPeriods(v.dataflow().id(), v.series().id(), v.stage());
    int pages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
    int page = Math.min(Math.max(vpage, 0), pages - 1);
    List<SeriesValue> values =
        observationService.findSeriesPage(
            v.dataflow().id(), v.series().id(), v.stage(), PAGE_SIZE, page * PAGE_SIZE);
    model.addAttribute("tableRows", rows(v, values));
    model.addAttribute("vpage", page);
    model.addAttribute("vpages", pages);
    model.addAttribute("vtotal", total);
    model.addAttribute("vfirst", total == 0 ? 0 : page * PAGE_SIZE + 1);
    model.addAttribute("vlast", Math.min(total, (page + 1) * PAGE_SIZE));
  }

  /** The row of one period, or a short note if the period is gone (deleted, never published). */
  private String rowOrGone(View v, String timePeriod, String goneMessage, Model model) {
    List<ObsRow> rows = rowsFor(v, List.of(timePeriod));
    if (rows.isEmpty()) {
      model.addAttribute(
          "goneMessage", goneMessage == null ? timePeriod + " has no value." : goneMessage);
      return "explore/fragments/series-parts :: goneRow";
    }
    model.addAttribute("row", rows.getFirst());
    return "explore/fragments/series-parts :: valueRow";
  }

  private List<ObsRow> rowsFor(View v, List<String> periods) {
    // current values of these periods (Alpha: deleted-but-published periods included)
    Map<String, String> current =
        observationService.findSeriesValuesAt(
            v.dataflow().id(), v.series().id(), v.stage(), periods, null);
    List<SeriesValue> values = new ArrayList<>();
    Map<String, String> published =
        v.alpha()
            ? observationService.findSeriesValuesAt(
                v.dataflow().id(), v.series().id(), ObservationStage.BETA, periods, null)
            : Map.of();
    for (String p : periods) {
      if (current.containsKey(p)) {
        values.add(new SeriesValue(p, current.get(p), false));
      } else if (published.containsKey(p)) {
        values.add(new SeriesValue(p, published.get(p), true));
      }
    }
    return rows(v, values);
  }

  private List<ObsRow> rows(View v, List<SeriesValue> values) {
    List<String> periods = values.stream().map(SeriesValue::timePeriod).toList();
    Long df = v.dataflow().id();
    Long sid = v.series().id();
    Map<String, Integer> revisions =
        observationService.findRevisionCounts(df, sid, v.stage(), periods);
    Map<String, String> published =
        v.alpha()
            ? observationService.findSeriesValuesAt(df, sid, ObservationStage.BETA, periods, null)
            : Map.of();
    Map<String, String> then =
        v.asOfInstant() == null
            ? Map.of()
            : observationService.findSeriesValuesAt(df, sid, v.stage(), periods, v.asOfInstant());
    List<ObsRow> rows = new ArrayList<>();
    for (SeriesValue sv : values) {
      List<String> current = measureValues(v, sv.measureValuesJson());
      String pending = null;
      if (v.alpha()) {
        String pub = published.get(sv.timePeriod());
        if (sv.deleted()) {
          pending = ObsRow.DELETED;
        } else if (pub == null) {
          pending = "new";
        } else if (!pub.equals(sv.measureValuesJson())) {
          // same comparison as the promotion: the stored JSON text
          pending = String.join(" / ", measureValues(v, pub));
        }
      }
      rows.add(
          new ObsRow(
              sv.timePeriod(),
              current,
              v.asOfInstant() == null
                  ? null
                  : then.containsKey(sv.timePeriod())
                      ? measureValues(v, then.get(sv.timePeriod()))
                      : v.measures().stream().map(m -> "").toList(),
              revisions.getOrDefault(sv.timePeriod(), 1),
              pending));
    }
    return rows;
  }

  private List<String> measureValues(View v, String json) {
    Map<Long, String> values = observationService.parseMeasureValues(json);
    return v.measures().stream().map(m -> values.getOrDefault(m.id(), "")).toList();
  }

  private DataStructureDefinition dsd(Long dataflowId) {
    return dsdService.findById(dataflowService.findById(dataflowId).dsdId());
  }

  private static List<Measure> measures(DataStructureDefinition dsd) {
    return dsd.measures().stream().sorted(Comparator.comparing(Measure::id)).toList();
  }

  private static String label(
      Map<Long, String> values,
      List<Measure> measures,
      Map<Long, SeriesStructure.ConceptRef> concepts) {
    if (measures.size() == 1) {
      return values.getOrDefault(measures.getFirst().id(), "");
    }
    return measures.stream()
        .map(
            m ->
                (concepts.containsKey(m.conceptId()) ? concepts.get(m.conceptId()).code() : "#")
                    + "="
                    + values.getOrDefault(m.id(), ""))
        .collect(Collectors.joining(", "));
  }

  private static Double number(String value) {
    try {
      return value == null || value.isBlank() ? null : Double.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Map<Long, String> measureValues(Map<String, String> params) {
    Map<Long, String> values = new HashMap<>();
    params.forEach(
        (key, value) -> {
          if (key.startsWith("measure_")) {
            values.put(Long.valueOf(key.substring(8)), value);
          }
        });
    return values;
  }

  /** The table view of this dataflow a series was opened from, if {@code ret} is one. */
  static String tableReturn(Long dataflowId, String ret) {
    return ret != null
            && ret.startsWith("/explore/dataflows/" + dataflowId + "?view=table")
            && ret.matches("[A-Za-z0-9/?&=%._~_+,:-]*")
        ? ret
        : null;
  }

  /** "Table (DEMO01 · _T · 2026-Q2)": the filter codes of the table URL. */
  static String tableLabel(String table) {
    List<String> codes = new ArrayList<>();
    for (String part : table.substring(table.indexOf('?') + 1).split("&")) {
      int eq = part.indexOf('=');
      String name = eq < 0 ? part : part.substring(0, eq);
      if (eq > 0 && (name.startsWith(KeyFilters.PARAM_PREFIX) || name.equals("time"))) {
        codes.add(java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return codes.isEmpty() ? "Table" : "Table (" + String.join(" · ", codes) + ")";
  }

  // --- rows ------------------------------------------------------------------------------------

  /**
   * @param revisions distinct successive values of the period (1 = never revised)
   * @param pending Alpha view: null = as published, "new", "deleted" or the published values
   */
  public record ObsRow(
      String timePeriod,
      List<String> values,
      List<String> asOfValues,
      int revisions,
      String pending) {

    static final String DELETED = "deleted";

    public boolean deleted() {
      return DELETED.equals(pending);
    }

    public boolean revised() {
      return revisions > 1;
    }
  }

  public record Revision(String values, String validFrom, String validTo, String comment) {}

  public record MeasureColumn(String header, String tooltip) {}
}
