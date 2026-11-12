package de.sdmxdw.web.explore;

import de.sdmxdw.data.audit.AuditLogService;
import de.sdmxdw.data.observation.Observation;
import de.sdmxdw.data.observation.ObservationQueryRepository.PendingChange;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.data.observation.ObservationStage;
import de.sdmxdw.structure.codelist.Code;
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
import de.sdmxdw.web.ConceptLabels;
import de.sdmxdw.web.KeyFilters;
import de.sdmxdw.web.Roles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The dataflow page of the explorer - one place for everyone. Economists see published (Beta) data;
 * Datenmanager additionally get the stage switch (Alpha), the observation table with recording and
 * correction, pending changes (Alpha vs Beta), promotion and the audit log. The services enforce
 * the role rules; this controller only decides what to show.
 */
@Controller
@RequestMapping("/explore/dataflows/{dataflowId}")
public class ExploreDataflowController {

  static final int TREE_LIMIT = 50;
  private static final int PAGE_SIZE = 50;
  private static final int AUDIT_PAGE_SIZE = 20;

  /** Tabs of the dataflow page; pending and activity are for Datenmanager only. */
  enum View {
    series,
    pending,
    activity
  }

  private final DataflowService dataflowService;
  private final DataStructureDefinitionService dsdService;
  private final ConceptSchemeService conceptSchemeService;
  private final CodelistService codelistService;
  private final ObservationService observationService;
  private final AuditLogService auditLogService;

  public ExploreDataflowController(
      DataflowService dataflowService,
      DataStructureDefinitionService dsdService,
      ConceptSchemeService conceptSchemeService,
      CodelistService codelistService,
      ObservationService observationService,
      AuditLogService auditLogService) {
    this.dataflowService = dataflowService;
    this.dsdService = dsdService;
    this.conceptSchemeService = conceptSchemeService;
    this.codelistService = codelistService;
    this.observationService = observationService;
    this.auditLogService = auditLogService;
  }

  // --- page ------------------------------------------------------------------------------------

  @GetMapping
  public String page(
      @PathVariable Long dataflowId,
      @RequestParam(required = false) String view,
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String period,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam MultiValueMap<String, String> params,
      Model model) {
    boolean dm = Roles.isDatenmanager();
    View selectedView = parseView(view, dm);
    ObservationStage selectedStage = parseStage(stage, dm);
    Context ctx = context(dataflowId);
    KeyFilters.Selection selection = ctx.selection(params);

    MultiValueMap<String, String> keep = new LinkedMultiValueMap<>(params);
    keep.remove("page");
    keep.remove("view");
    Map<String, String> tabUrls = new LinkedHashMap<>();
    for (View v : View.values()) {
      MultiValueMap<String, String> p = new LinkedMultiValueMap<>(keep);
      p.set("view", v.name());
      tabUrls.put(v.name(), url(dataflowId, p));
    }
    MultiValueMap<String, String> withoutPage = new LinkedMultiValueMap<>(keep);
    withoutPage.set("view", selectedView.name());
    String pageBase = url(dataflowId, withoutPage);
    MultiValueMap<String, String> otherStage = new LinkedMultiValueMap<>(withoutPage);
    otherStage.set("stage", selectedStage == ObservationStage.BETA ? "ALPHA" : "BETA");

    model.addAttribute("dataflow", ctx.dataflow());
    model.addAttribute("isDatenmanager", dm);
    model.addAttribute("view", selectedView.name());
    model.addAttribute("stage", selectedStage.name());
    model.addAttribute("stageToggleUrl", url(dataflowId, otherStage));
    model.addAttribute("tabUrls", tabUrls);
    model.addAttribute("pageUrlPrefix", pageBase + (pageBase.contains("?") ? "&" : "?"));
    model.addAttribute("keyDimensionOrder", String.join(".", ctx.keyCodes()));
    model.addAttribute("filters", selection.filters());
    model.addAttribute("query", q);
    // facets of the stage that is shown (pending changes are Alpha observations)
    ObservationStage facetStage =
        selectedView == View.pending ? ObservationStage.ALPHA : selectedStage;
    MultiValueMap<String, String> facetParams = new LinkedMultiValueMap<>();
    keep.forEach(
        (k, v) -> {
          if (k.startsWith(KeyFilters.PARAM_PREFIX) || k.equals("q")) {
            facetParams.put(k, v);
          }
        });
    model.addAttribute(
        "facetsUrl",
        UriComponentsBuilder.fromPath("/explore/dataflows/{id}/facets")
            .queryParam("stage", facetStage.name())
            .buildAndExpand(dataflowId)
            .toUriString());
    model.addAttribute(
        "facetsQuery",
        UriComponentsBuilder.newInstance().queryParams(facetParams).build().encode().getQuery());
    if (dm) {
      model.addAttribute("pendingCount", observationService.countPendingChanges(dataflowId));
    }

    switch (selectedView) {
      case series -> {
        String valuePeriod = period == null || period.isBlank() ? null : period.strip();
        ObservationService.SeriesPage result =
            observationService.findSeriesPage(
                dataflowId,
                selectedStage,
                selection.codesByPosition(),
                q,
                valuePeriod,
                page,
                PAGE_SIZE);
        model.addAttribute("result", result);
        model.addAttribute("rows", ctx.seriesRows(result));
        model.addAttribute("period", valuePeriod);
        if (dm) {
          // "New series": key, first period and values (#25)
          model.addAttribute("keyDimensionFields", ctx.dimensionFields(false));
          model.addAttribute("timeDimensionFields", ctx.dimensionFields(true));
          model.addAttribute("measureFields", ctx.measureFields());
        }
      }
      case pending -> {
        ObservationService.PendingPage result =
            observationService.findPendingChanges(
                dataflowId, selection.codesByPosition(), q, page, PAGE_SIZE);
        model.addAttribute("result", result);
        model.addAttribute("rows", result.rows().stream().map(ctx::pendingRow).toList());
        model.addAttribute("measureColumns", ctx.measureColumns());
      }
      case activity ->
          model.addAttribute(
              "auditPage", auditLogService.findByDataflow(dataflowId, page, AUDIT_PAGE_SIZE));
    }
    return "explore/dataflow";
  }

  @PostMapping("/promote")
  public String promote(
      @PathVariable Long dataflowId,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    ObservationService.PromotionResult result =
        observationService.promoteToBeta(dataflowId, authentication.getName());
    redirectAttributes.addFlashAttribute(
        "flash",
        result.promoted()
            + " observation(s) published to Beta"
            + (result.unchanged() > 0 ? ", " + result.unchanged() + " unchanged" : "")
            + (result.deleted() > 0 ? ", " + result.deleted() + " deleted" : "")
            + ".");
    return "redirect:/explore/dataflows/" + dataflowId + "?view=pending";
  }

  // --- series list in the explorer tree and dimension facets ------------------------------------

  /**
   * Series list in the explorer tree: only the first {@value #TREE_LIMIT} series plus the total;
   * the filter field re-requests just the list ({@code listOnly=true}) so it keeps its focus.
   */
  @GetMapping("/series-fragment")
  public String seriesFragment(
      @PathVariable Long dataflowId,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "false") boolean listOnly,
      @RequestParam MultiValueMap<String, String> params,
      Model model) {
    Context ctx = context(dataflowId);
    KeyFilters.Selection selection = ctx.selection(params);
    ObservationService.SeriesPage result =
        observationService.findSeriesPage(
            dataflowId, ObservationStage.BETA, selection.codesByPosition(), q, 0, TREE_LIMIT);
    MultiValueMap<String, String> pageParams = new LinkedMultiValueMap<>(params);
    pageParams.remove("listOnly");
    model.addAttribute("dataflowId", dataflowId);
    model.addAttribute("rows", ctx.seriesRows(result));
    model.addAttribute("result", result);
    model.addAttribute("query", q);
    model.addAttribute("dataflowPageUrl", url(dataflowId, pageParams));
    // the filter field is only worth showing for dataflows with more series than fit the tree
    model.addAttribute(
        "showFilter",
        result.total() > TREE_LIMIT || (q != null && !q.isBlank()) || selection.active());
    return listOnly
        ? "explore/fragments/dataflow-series :: list"
        : "explore/fragments/dataflow-series";
  }

  /**
   * Facets for the dimension pickers: per key dimension the codes that occur in the dataflow's
   * series of the stage, with series counts under the selections of the other dimensions.
   */
  @GetMapping(value = "/facets", produces = "application/json")
  @ResponseBody
  public Facets.Response facets(
      @PathVariable Long dataflowId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String stage,
      @RequestParam MultiValueMap<String, String> params) {
    ObservationStage facetStage = parseStage(stage, Roles.isDatenmanager());
    Context ctx = context(dataflowId);
    KeyFilters.Selection selection = ctx.selection(params);
    Map<Integer, Map<String, Long>> counts =
        observationService.findKeyFacets(
            dataflowId, facetStage, selection.filters().size(), selection.codesByPosition(), q);
    List<Facets.Dimension> dimensions = new ArrayList<>();
    for (int i = 0; i < selection.filters().size(); i++) {
      KeyFilters.Filter f = selection.filters().get(i);
      Map<String, String> names = new HashMap<>();
      f.options().forEach(o -> names.put(o.code(), o.name()));
      Map<String, Long> dimCounts = new TreeMap<>(counts.getOrDefault(i + 1, Map.of()));
      f.selected().forEach(code -> dimCounts.putIfAbsent(code, 0L));
      dimensions.add(
          new Facets.Dimension(
              f.param(),
              f.code(),
              f.label(),
              dimCounts.entrySet().stream()
                  .map(
                      e ->
                          new Facets.Value(
                              e.getKey(), names.getOrDefault(e.getKey(), ""), e.getValue()))
                  .toList(),
              f.selected().stream().sorted().toList()));
    }
    long total =
        observationService
            .findSeriesPage(dataflowId, facetStage, selection.codesByPosition(), q, 0, 1)
            .total();
    return new Facets.Response(total, dimensions);
  }

  // --- new series (#25) ------------------------------------------------------------------------

  /** Creates a series with its first period in Alpha and opens it. */
  @PostMapping("/series")
  public String createSeries(
      @PathVariable Long dataflowId,
      @RequestParam(required = false) String comment,
      @RequestParam Map<String, String> allParams,
      Authentication authentication,
      RedirectAttributes redirectAttributes) {
    Map<Long, String> dimensionValues = new HashMap<>();
    allParams.forEach(
        (key, value) -> {
          if (key.startsWith("dim_")) {
            dimensionValues.put(Long.valueOf(key.substring(4)), value);
          }
        });
    try {
      Observation created =
          observationService.createSeries(
              dataflowId,
              dimensionValues,
              measureValues(allParams),
              comment,
              authentication.getName());
      redirectAttributes.addFlashAttribute(
          "flash", "Saved in Alpha - published with the next promotion.");
      return "redirect:/explore/dataflows/"
          + dataflowId
          + "/series/"
          + created.seriesId()
          + "?stage=ALPHA";
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("error", e.getMessage());
      return "redirect:/explore/dataflows/" + dataflowId + "?stage=ALPHA";
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

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

  private static View parseView(String view, boolean dm) {
    View v;
    try {
      v = view == null ? View.series : View.valueOf(view);
    } catch (IllegalArgumentException e) {
      v = View.series;
    }
    return !dm && (v == View.pending || v == View.activity) ? View.series : v;
  }

  /** Alpha only for Datenmanager - everyone else always sees published data. */
  static ObservationStage parseStage(String stage, boolean dm) {
    return dm && "ALPHA".equalsIgnoreCase(stage) ? ObservationStage.ALPHA : ObservationStage.BETA;
  }

  private static String url(Long dataflowId, MultiValueMap<String, String> params) {
    return UriComponentsBuilder.fromPath("/explore/dataflows/{id}")
        .queryParams(params)
        .buildAndExpand(dataflowId)
        .encode()
        .toUriString();
  }

  private Context context(Long dataflowId) {
    Dataflow dataflow = dataflowService.findById(dataflowId);
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    Map<Long, String> labels = ConceptLabels.byConceptId(conceptSchemeService);
    Map<Long, String> codes = ConceptCodes.byConceptId(conceptSchemeService);
    List<Dimension> keyDimensions =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() != DataType.DATE)
            .sorted(Comparator.comparingInt(Dimension::position))
            .toList();
    List<Map<String, String>> codeNames = new ArrayList<>();
    for (Dimension d : keyDimensions) {
      Map<String, String> names = new LinkedHashMap<>();
      if (d.codelistId() != null) {
        codelistService.findById(d.codelistId()).codes().stream()
            .sorted(Comparator.comparing(Code::code))
            .forEach(c -> names.put(c.code(), c.name()));
      }
      codeNames.add(names);
    }
    List<Measure> measures =
        dsd.measures().stream().sorted(Comparator.comparing(Measure::id)).toList();
    return new Context(
        dataflow,
        dsd,
        labels,
        codes,
        keyDimensions,
        codeNames,
        measures,
        observationService::parseMeasureValues);
  }

  /** Structure information of one dataflow needed to render its tables. */
  private record Context(
      Dataflow dataflow,
      DataStructureDefinition dsd,
      Map<Long, String> conceptLabels,
      Map<Long, String> conceptCodes,
      List<Dimension> keyDimensions,
      List<Map<String, String>> codeNamesByPosition,
      List<Measure> measures,
      Function<String, Map<Long, String>> parser) {

    KeyFilters.Selection selection(MultiValueMap<String, String> params) {
      return KeyFilters.select(
          keyDimensions, codeNamesByPosition, conceptCodes, conceptLabels, params);
    }

    List<String> keyCodes() {
      return keyDimensions.stream()
          .map(d -> conceptCodes.getOrDefault(d.conceptId(), "#" + d.conceptId()))
          .toList();
    }

    List<MeasureColumn> measureColumns() {
      return measures.stream()
          .map(
              m ->
                  new MeasureColumn(
                      conceptCodes.getOrDefault(m.conceptId(), "#" + m.conceptId()),
                      conceptLabels.getOrDefault(m.conceptId(), "")))
          .toList();
    }

    List<CodeCell> keyCells(String seriesKey) {
      String[] values = seriesKey.split("\\.", -1);
      List<CodeCell> cells = new ArrayList<>();
      for (int i = 0; i < values.length; i++) {
        String name =
            i < codeNamesByPosition.size() ? codeNamesByPosition.get(i).get(values[i]) : null;
        cells.add(new CodeCell(values[i], name == null ? "" : name));
      }
      return cells;
    }

    List<String> values(Map<Long, String> byMeasureId) {
      return measures.stream().map(m -> byMeasureId.getOrDefault(m.id(), "")).toList();
    }

    String keyLabel(String seriesKey) {
      String[] values = seriesKey.split("\\.", -1);
      List<String> parts = new ArrayList<>();
      for (int i = 0; i < values.length; i++) {
        String code =
            i < keyDimensions.size()
                ? conceptCodes.getOrDefault(keyDimensions.get(i).conceptId(), "dim" + i)
                : "dim" + i;
        String name =
            i < codeNamesByPosition.size() ? codeNamesByPosition.get(i).get(values[i]) : null;
        parts.add(code + "=" + values[i] + (name == null ? "" : " (" + name + ")"));
      }
      return String.join(", ", parts);
    }

    String measuresLabel(String measureValuesJson) {
      Map<Long, String> values = parser.apply(measureValuesJson);
      return measures.stream()
          .map(
              m ->
                  conceptCodes.getOrDefault(m.conceptId(), "#" + m.conceptId())
                      + "="
                      + values.getOrDefault(m.id(), ""))
          .collect(Collectors.joining(", "));
    }

    List<SeriesRow> seriesRows(ObservationService.SeriesPage page) {
      return page.rows().stream()
          .map(
              r ->
                  new SeriesRow(
                      r.seriesId(),
                      keyLabel(r.seriesKey()),
                      r.seriesKey(),
                      r.firstPeriod(),
                      r.lastPeriod(),
                      r.count(),
                      r.latestMeasureValuesJson() == null
                          ? null
                          : measuresLabel(r.latestMeasureValuesJson())))
          .toList();
    }

    PendingRow pendingRow(PendingChange p) {
      return new PendingRow(
          p.seriesId(),
          p.seriesKey(),
          keyCells(p.seriesKey()),
          p.timePeriod(),
          p.betaMeasureValuesJson() == null
              ? null
              : values(parser.apply(p.betaMeasureValuesJson())),
          p.deleted() ? null : values(parser.apply(p.alphaMeasureValuesJson())),
          p.validFrom().toString(),
          p.recordedBy(),
          p.comment());
    }

    List<DimensionField> dimensionFields(boolean timeOnly) {
      List<DimensionField> fields = new ArrayList<>();
      for (Dimension d : dsd.dimensions()) {
        if ((d.dataType() == DataType.DATE) != timeOnly) {
          continue;
        }
        String label = conceptLabels.getOrDefault(d.conceptId(), "#" + d.conceptId());
        int position = keyDimensions.indexOf(d);
        List<KeyFilters.Option> options =
            position < 0
                ? List.of()
                : codeNamesByPosition.get(position).entrySet().stream()
                    .map(
                        e ->
                            new KeyFilters.Option(
                                e.getKey(), e.getValue(), e.getKey() + " - " + e.getValue()))
                    .toList();
        fields.add(
            new DimensionField(
                d.id(),
                d.position(),
                label,
                d.codelistId() != null,
                options,
                inputType(d.dataType())));
      }
      fields.sort(Comparator.comparingInt(DimensionField::position));
      return fields;
    }

    List<MeasureField> measureFields() {
      return measures.stream()
          .map(
              m ->
                  new MeasureField(
                      m.id(),
                      conceptLabels.getOrDefault(m.conceptId(), "#" + m.conceptId()),
                      inputType(m.dataType())))
          .toList();
    }

    private static String inputType(DataType type) {
      return type == DataType.NUMBER ? "number" : "text";
    }
  }

  // --- view models -------------------------------------------------------------------------------

  public record SeriesRow(
      Long seriesId,
      String keyLabel,
      String seriesKey,
      String firstPeriod,
      String lastPeriod,
      int count,
      String latestValue) {}

  public record CodeCell(String code, String name) {}

  public record MeasureColumn(String code, String label) {}

  /**
   * @param betaValues null = new, never published
   * @param alphaValues null = deleted in Alpha (#25)
   */
  public record PendingRow(
      Long seriesId,
      String seriesKey,
      List<CodeCell> key,
      String timePeriod,
      List<String> betaValues,
      List<String> alphaValues,
      String validFrom,
      String recordedBy,
      String comment) {

    public boolean deleted() {
      return alphaValues == null;
    }
  }

  public record DimensionField(
      Long id,
      int position,
      String label,
      boolean coded,
      List<KeyFilters.Option> options,
      String inputType) {}

  public record MeasureField(Long id, String label, String inputType) {}
}
