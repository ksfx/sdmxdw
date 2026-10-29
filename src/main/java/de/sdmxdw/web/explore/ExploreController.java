package de.sdmxdw.web.explore;

import de.sdmxdw.data.observation.Observation;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.data.observation.ObservationStage;
import de.sdmxdw.data.series.Series;
import de.sdmxdw.structure.categorisation.CategorisationService;
import de.sdmxdw.structure.category.Category;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import de.sdmxdw.structure.codelist.Code;
import de.sdmxdw.structure.codelist.Codelist;
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
import de.sdmxdw.web.AsOf;
import de.sdmxdw.web.ConceptLabels;
import de.sdmxdw.web.MarkdownRenderer;
import de.sdmxdw.web.Roles;
import de.sdmxdw.web.rest.SdmxDataWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Ökonom-facing exploration UI: category tree, dataflow/series drill-down, charts, export. */
@Controller
@RequestMapping("/explore")
public class ExploreController {

  private final CategorySchemeService categorySchemeService;
  private final CategorisationService categorisationService;
  private final DataflowService dataflowService;
  private final DataStructureDefinitionService dsdService;
  private final ConceptSchemeService conceptSchemeService;
  private final ObservationService observationService;
  private final CodelistService codelistService;
  private final MarkdownRenderer markdownRenderer;

  public ExploreController(
      CategorySchemeService categorySchemeService,
      CategorisationService categorisationService,
      DataflowService dataflowService,
      DataStructureDefinitionService dsdService,
      ConceptSchemeService conceptSchemeService,
      ObservationService observationService,
      CodelistService codelistService,
      MarkdownRenderer markdownRenderer) {
    this.codelistService = codelistService;
    this.markdownRenderer = markdownRenderer;
    this.categorySchemeService = categorySchemeService;
    this.categorisationService = categorisationService;
    this.dataflowService = dataflowService;
    this.dsdService = dsdService;
    this.conceptSchemeService = conceptSchemeService;
    this.observationService = observationService;
  }

  @GetMapping
  public String index(@RequestParam(required = false) String q, Model model) {
    if (q != null && !q.isBlank()) {
      model.addAttribute("query", q);
      model.addAttribute("matchingDataflows", searchDataflows(q));
      return "explore/search-results";
    }
    List<CategorySchemeTree> trees =
        categorySchemeService.findAll().stream()
            .map(
                scheme ->
                    new CategorySchemeTree(
                        scheme.id(),
                        scheme.artefactId(),
                        scheme.name(),
                        scheme.description() != null,
                        buildTree(scheme)))
            .filter(tree -> !tree.roots().isEmpty())
            .toList();
    model.addAttribute("trees", trees);
    return "explore/index";
  }

  /** "About" panel with the rendered Markdown description; used by both explorer pages. */
  @GetMapping("/category-schemes/{schemeId}/about")
  public String schemeAbout(@PathVariable Long schemeId, Model model) {
    CategoryScheme scheme = categorySchemeService.findById(schemeId);
    model.addAttribute("scheme", scheme);
    model.addAttribute("descriptionHtml", markdownRenderer.render(scheme.description()));
    return "explore/fragments/scheme-about :: about";
  }

  @GetMapping("/categories/{categoryId}")
  public String category(@PathVariable Long categoryId, Model model) {
    Category category = findCategory(categoryId);
    model.addAttribute("category", category);
    model.addAttribute("dataflows", dataflowsForCategory(categoryId));
    return "explore/category";
  }

  @GetMapping("/categories/{categoryId}/dataflows-fragment")
  public String categoryDataflowsFragment(@PathVariable Long categoryId, Model model) {
    model.addAttribute("dataflows", dataflowsForCategory(categoryId));
    return "explore/fragments/category-dataflows";
  }

  @GetMapping("/dataflows/{dataflowId}/series/{seriesId}")
  public String series(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String stage,
      Model model) {
    populateSeriesModel(dataflowId, seriesId, asOf, stage, model);
    model.addAttribute("panelMode", false);
    return "explore/series";
  }

  @GetMapping("/dataflows/{dataflowId}/series/{seriesId}/panel")
  public String seriesPanel(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String stage,
      Model model) {
    populateSeriesModel(dataflowId, seriesId, asOf, stage, model);
    model.addAttribute("panelMode", true);
    return "explore/fragments/series-panel";
  }

  /**
   * "2026-10-27" means "as published at the end of that day (UTC)", "2026-10-27T12:00" that minute
   * (UTC); a full ISO instant is used as is - the same semantics as the asOf parameter of the SDMX
   * REST endpoint.
   */
  static Instant parseAsOf(String asOf) {
    return AsOf.parse(asOf);
  }

  private List<Dataflow> dataflowsForCategory(Long categoryId) {
    return categorisationService.findByCategoryId(categoryId).stream()
        .map(c -> dataflowService.findById(c.dataflowId()))
        .toList();
  }

  /** Value table alone (htmx): used after saving and by "Cancel" of the value editor. */
  @GetMapping("/dataflows/{dataflowId}/series/{seriesId}/value-table")
  public String valueTable(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String stage,
      @RequestParam(defaultValue = "false") boolean panelMode,
      Model model) {
    populateSeriesModel(dataflowId, seriesId, asOf, stage, model);
    model.addAttribute("panelMode", panelMode);
    return "explore/fragments/series-parts :: valueTable";
  }

  /** Inline editor for one time period of the value table (Datenmanager, Alpha view). */
  @GetMapping("/dataflows/{dataflowId}/series/{seriesId}/edit-value")
  public String editValue(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam String timePeriod,
      @RequestParam(required = false) String asOf,
      @RequestParam(defaultValue = "false") boolean panelMode,
      Model model) {
    populateSeriesModel(dataflowId, seriesId, asOf, ObservationStage.ALPHA.name(), model);
    Observation current =
        observationService
            .findCurrentOne(dataflowId, seriesId, timePeriod, ObservationStage.ALPHA)
            .orElseThrow();
    Map<Long, String> values = observationService.parseMeasureValues(current.measureValuesJson());
    @SuppressWarnings("unchecked")
    List<Long> measureIds = (List<Long>) model.getAttribute("measureIds");
    model.addAttribute("editPeriod", timePeriod);
    model.addAttribute(
        "editValues", measureIds.stream().map(id -> values.getOrDefault(id, "")).toList());
    model.addAttribute("panelMode", panelMode);
    return "explore/fragments/series-parts :: valueTable";
  }

  @PostMapping("/dataflows/{dataflowId}/series/{seriesId}/correct")
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
    Map<Long, String> measureValues = new HashMap<>();
    allParams.forEach(
        (key, value) -> {
          if (key.startsWith("measure_")) {
            measureValues.put(Long.valueOf(key.substring(8)), value);
          }
        });
    observationService.correctObservation(
        dataflowId, seriesId, timePeriod, measureValues, comment, authentication.getName());
    populateSeriesModel(dataflowId, seriesId, asOf, ObservationStage.ALPHA.name(), model);
    model.addAttribute("savedPeriod", timePeriod);
    model.addAttribute("panelMode", panelMode);
    return "explore/fragments/series-parts :: valueTable";
  }

  private void populateSeriesModel(
      Long dataflowId, Long seriesId, String asOf, String stageParam, Model model) {
    boolean dm = Roles.isDatenmanager();
    ObservationStage stage = ExploreDataflowController.parseStage(stageParam, dm);
    Instant asOfInstant = parseAsOf(asOf);
    Dataflow dataflow = dataflowService.findById(dataflowId);
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    Map<Long, ConceptRef> concepts = conceptRefs();
    Series series = observationService.findSeriesById(seriesId);
    List<Measure> measures =
        dsd.measures().stream().sorted(Comparator.comparing(Measure::id)).toList();
    Long firstMeasureId = measures.isEmpty() ? null : measures.get(0).id();

    List<Observation> observations = seriesObservations(dataflowId, seriesId, null, stage);
    Map<String, Map<Long, String>> asOfValues = new HashMap<>();
    if (asOfInstant != null) {
      seriesObservations(dataflowId, seriesId, asOfInstant, stage)
          .forEach(
              o ->
                  asOfValues.put(
                      o.timePeriod(),
                      observationService.parseMeasureValues(o.measureValuesJson())));
    }
    Map<String, List<Revision>> revisionsByPeriod = revisions(seriesId, stage, measures, concepts);
    // Alpha view: which values differ from what is published (= pending changes)
    Map<String, String> publishedJson = new HashMap<>();
    if (stage == ObservationStage.ALPHA) {
      seriesObservations(dataflowId, seriesId, null, ObservationStage.BETA)
          .forEach(o -> publishedJson.put(o.timePeriod(), o.measureValuesJson()));
    }

    List<String> chartLabels = observations.stream().map(Observation::timePeriod).toList();
    List<Double> chartValues =
        observations.stream()
            .map(
                o -> {
                  String value =
                      observationService
                          .parseMeasureValues(o.measureValuesJson())
                          .get(firstMeasureId);
                  return value == null ? null : Double.valueOf(value);
                })
            .toList();
    List<Double> chartAsOfValues =
        asOfInstant == null
            ? null
            : observations.stream()
                .map(
                    o -> {
                      Map<Long, String> v = asOfValues.get(o.timePeriod());
                      String value = v == null ? null : v.get(firstMeasureId);
                      return value == null ? null : Double.valueOf(value);
                    })
                .toList();
    List<ObsRow> tableRows =
        observations.stream()
            .map(
                o -> {
                  Map<Long, String> values =
                      observationService.parseMeasureValues(o.measureValuesJson());
                  Map<Long, String> then = asOfValues.get(o.timePeriod());
                  return new ObsRow(
                      o.timePeriod(),
                      measures.stream().map(m -> values.getOrDefault(m.id(), "")).toList(),
                      asOfInstant == null
                          ? null
                          : measures.stream()
                              .map(m -> then == null ? "" : then.getOrDefault(m.id(), ""))
                              .toList(),
                      revisionsByPeriod.getOrDefault(o.timePeriod(), List.of()),
                      stage == ObservationStage.ALPHA
                          ? pendingLabel(publishedJson.get(o.timePeriod()), o, measures)
                          : null);
                })
            .toList();
    List<MeasureColumn> measureColumns =
        measures.stream()
            .map(
                m -> {
                  ConceptRef concept = concepts.get(m.conceptId());
                  String header =
                      measures.size() == 1 ? "Value" : concept == null ? "#" : concept.code();
                  return new MeasureColumn(header, concept == null ? "" : concept.label());
                })
            .toList();

    model.addAttribute("dataflow", dataflow);
    model.addAttribute("dsd", dsd);
    model.addAttribute("seriesId", seriesId);
    model.addAttribute("seriesKey", series.seriesKey());
    model.addAttribute("keyDimensions", keyDimensionInfos(dsd, series.seriesKey(), concepts));
    model.addAttribute("measureColumns", measureColumns);
    model.addAttribute("chartLabels", chartLabels);
    model.addAttribute("chartValues", chartValues);
    model.addAttribute("chartAsOfValues", chartAsOfValues);
    model.addAttribute("tableRows", tableRows);
    model.addAttribute("asOf", asOf == null || asOf.isBlank() ? null : asOf.trim());
    model.addAttribute("asOfInstant", asOfInstant == null ? null : asOfInstant.toString());
    model.addAttribute(
        "revisedCount", revisionsByPeriod.values().stream().filter(r -> r.size() > 1).count());
    model.addAttribute("stage", stage.name());
    model.addAttribute("isDatenmanager", dm);
    model.addAttribute("canEdit", dm && stage == ObservationStage.ALPHA);
    model.addAttribute("measureIds", measures.stream().map(Measure::id).toList());
    model.addAttribute("pendingCount", tableRows.stream().filter(r -> r.pending() != null).count());
  }

  /** Same comparison as the promotion: the stored JSON text. */
  private String pendingLabel(String publishedJson, Observation alpha, List<Measure> measures) {
    if (publishedJson == null) {
      return "new";
    }
    if (publishedJson.equals(alpha.measureValuesJson())) {
      return null;
    }
    Map<Long, String> published = observationService.parseMeasureValues(publishedJson);
    return measures.stream()
        .map(m -> published.getOrDefault(m.id(), ""))
        .collect(Collectors.joining(" / "));
  }

  /**
   * Published (Beta) value history per time period. Consecutive vintages with identical values are
   * merged - they come from unchanged re-deliveries, not from revisions.
   */
  private Map<String, List<Revision>> revisions(
      Long seriesId,
      ObservationStage stage,
      List<Measure> measures,
      Map<Long, ConceptRef> concepts) {
    Map<String, List<Revision>> result = new LinkedHashMap<>();
    Map<String, Map<Long, String>> lastValues = new HashMap<>();
    for (Observation o : observationService.findSeriesHistory(seriesId, stage)) {
      Map<Long, String> values = observationService.parseMeasureValues(o.measureValuesJson());
      List<Revision> list = result.computeIfAbsent(o.timePeriod(), k -> new ArrayList<>());
      String validTo = o.validTo() == null ? null : o.validTo().toString();
      if (values.equals(lastValues.get(o.timePeriod())) && !list.isEmpty()) {
        Revision previous = list.remove(list.size() - 1);
        list.add(new Revision(previous.values(), previous.validFrom(), validTo, o.comment()));
      } else {
        String label =
            measures.size() == 1
                ? values.getOrDefault(measures.get(0).id(), "")
                : measures.stream()
                    .map(
                        m ->
                            (concepts.containsKey(m.conceptId())
                                    ? concepts.get(m.conceptId()).code()
                                    : "#")
                                + "="
                                + values.getOrDefault(m.id(), ""))
                    .collect(Collectors.joining(", "));
        list.add(new Revision(label, o.validFrom().toString(), validTo, o.comment()));
      }
      lastValues.put(o.timePeriod(), values);
    }
    return result;
  }

  private List<DimensionInfo> keyDimensionInfos(
      DataStructureDefinition dsd, String seriesKey, Map<Long, ConceptRef> concepts) {
    String[] values = seriesKey.split("\\.", -1);
    List<Dimension> keyDimensions =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() != DataType.DATE)
            .sorted(Comparator.comparingInt(Dimension::position))
            .toList();
    List<DimensionInfo> infos = new ArrayList<>();
    for (int i = 0; i < keyDimensions.size(); i++) {
      Dimension dimension = keyDimensions.get(i);
      String value = i < values.length ? values[i] : "";
      ConceptRef concept = concepts.get(dimension.conceptId());
      Codelist codelist =
          dimension.codelistId() == null ? null : codelistService.findById(dimension.codelistId());
      String valueName =
          codelist == null
              ? null
              : codelist.codes().stream()
                  .filter(c -> c.code().equals(value))
                  .map(Code::name)
                  .findFirst()
                  .orElse(null);
      infos.add(
          new DimensionInfo(
              concept == null ? "#" + dimension.conceptId() : concept.code(),
              concept == null ? "" : concept.name(),
              concept == null ? null : concept.schemeId(),
              value,
              valueName,
              codelist == null ? null : codelist.id(),
              codelist == null ? null : codelist.artefactId()));
    }
    return infos;
  }

  private Map<Long, ConceptRef> conceptRefs() {
    Map<Long, ConceptRef> refs = new HashMap<>();
    for (ConceptScheme scheme : conceptSchemeService.findAll()) {
      scheme
          .concepts()
          .forEach(
              c ->
                  refs.put(
                      c.id(),
                      new ConceptRef(c.code(), c.name(), scheme.id(), scheme.artefactId())));
    }
    return refs;
  }

  private Map<Long, String> conceptLabels() {
    return ConceptLabels.byConceptId(conceptSchemeService);
  }

  @GetMapping("/dataflows/{dataflowId}/series/{seriesId}/export.csv")
  public void exportCsv(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      HttpServletResponse response)
      throws IOException {
    Dataflow dataflow = dataflowService.findById(dataflowId);
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    Map<Long, String> conceptLabels = ConceptLabels.byConceptId(conceptSchemeService);
    List<Measure> measures =
        dsd.measures().stream().sorted(Comparator.comparing(Measure::id)).toList();
    List<Observation> observations = seriesObservations(dataflowId, seriesId, parseAsOf(asOf));

    response.setContentType("text/csv");
    response.setHeader("Content-Disposition", "attachment; filename=series-" + seriesId + ".csv");
    PrintWriter writer = response.getWriter();
    StringBuilder header = new StringBuilder("time_period");
    for (Measure measure : measures) {
      header
          .append(',')
          .append(csvEscape(conceptLabels.getOrDefault(measure.conceptId(), "measure")));
    }
    writer.println(header);
    for (Observation observation : observations) {
      Map<Long, String> values =
          observationService.parseMeasureValues(observation.measureValuesJson());
      StringBuilder line = new StringBuilder(csvEscape(observation.timePeriod()));
      for (Measure measure : measures) {
        line.append(',').append(csvEscape(values.getOrDefault(measure.id(), "")));
      }
      writer.println(line);
    }
    writer.flush();
  }

  @GetMapping("/dataflows/{dataflowId}/series/{seriesId}/export.xlsx")
  public void exportExcel(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      HttpServletResponse response)
      throws IOException {
    Dataflow dataflow = dataflowService.findById(dataflowId);
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    Map<Long, String> conceptLabels = ConceptLabels.byConceptId(conceptSchemeService);
    List<Measure> measures =
        dsd.measures().stream().sorted(Comparator.comparing(Measure::id)).toList();
    List<Observation> observations = seriesObservations(dataflowId, seriesId, parseAsOf(asOf));

    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      XSSFSheet sheet = workbook.createSheet("series-" + seriesId);
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("time_period");
      for (int i = 0; i < measures.size(); i++) {
        header
            .createCell(i + 1)
            .setCellValue(conceptLabels.getOrDefault(measures.get(i).conceptId(), "measure"));
      }
      int rowIndex = 1;
      for (Observation observation : observations) {
        Map<Long, String> values =
            observationService.parseMeasureValues(observation.measureValuesJson());
        Row row = sheet.createRow(rowIndex++);
        row.createCell(0).setCellValue(observation.timePeriod());
        for (int i = 0; i < measures.size(); i++) {
          String value = values.get(measures.get(i).id());
          if (value != null) {
            try {
              row.createCell(i + 1).setCellValue(Double.parseDouble(value));
            } catch (NumberFormatException e) {
              row.createCell(i + 1).setCellValue(value);
            }
          }
        }
      }
      response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
      response.setHeader(
          "Content-Disposition", "attachment; filename=series-" + seriesId + ".xlsx");
      workbook.write(response.getOutputStream());
    }
  }

  @GetMapping("/dataflows/{dataflowId}/series/{seriesId}/export-sdmx21.xml")
  public void exportSdmx21(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      HttpServletResponse response)
      throws IOException {
    writeSdmxMl(
        dataflowId,
        seriesId,
        parseAsOf(asOf),
        SdmxDataWriter.Format.STRUCTURE_SPECIFIC_21,
        "sdmx21",
        response);
  }

  @GetMapping("/dataflows/{dataflowId}/series/{seriesId}/export-sdmx31.xml")
  public void exportSdmx31(
      @PathVariable Long dataflowId,
      @PathVariable Long seriesId,
      @RequestParam(required = false) String asOf,
      HttpServletResponse response)
      throws IOException {
    writeSdmxMl(
        dataflowId,
        seriesId,
        parseAsOf(asOf),
        SdmxDataWriter.Format.STRUCTURE_SPECIFIC_31,
        "sdmx31",
        response);
  }

  private void writeSdmxMl(
      Long dataflowId,
      Long seriesId,
      Instant asOf,
      SdmxDataWriter.Format format,
      String suffix,
      HttpServletResponse response)
      throws IOException {
    Dataflow dataflow = dataflowService.findById(dataflowId);
    DataStructureDefinition dsd = dsdService.findById(dataflow.dsdId());
    Map<Long, ConceptRef> concepts = conceptRefs();
    Series series = observationService.findSeriesById(seriesId);
    List<Measure> measures =
        dsd.measures().stream().sorted(Comparator.comparing(Measure::id)).toList();
    String timeDimensionCode =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() == DataType.DATE)
            .map(d -> concepts.get(d.conceptId()))
            .filter(java.util.Objects::nonNull)
            .map(ConceptRef::code)
            .findFirst()
            .orElse("TIME_PERIOD");

    List<String> dimensionIds = new ArrayList<>();
    List<String> keyValues = new ArrayList<>();
    keyDimensionInfos(dsd, series.seriesKey(), concepts)
        .forEach(
            d -> {
              dimensionIds.add(d.conceptCode());
              keyValues.add(d.value());
            });
    List<SdmxDataWriter.ObsData> observations =
        seriesObservations(dataflowId, seriesId, asOf).stream()
            .map(
                o -> {
                  Map<Long, String> values =
                      observationService.parseMeasureValues(o.measureValuesJson());
                  return new SdmxDataWriter.ObsData(
                      o.timePeriod(), measures.stream().map(m -> values.get(m.id())).toList());
                })
            .toList();
    SdmxDataWriter.DataMessage message =
        new SdmxDataWriter.DataMessage(
            dataflow.agencyId(),
            dataflow.artefactId(),
            dataflow.version(),
            dimensionIds,
            timeDimensionCode,
            measures.stream().map(m -> concepts.get(m.conceptId()).code()).toList(),
            List.of(new SdmxDataWriter.SeriesData(keyValues, observations)),
            Map.of(),
            Map.of());

    response.setContentType("application/xml");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(
        "Content-Disposition", "attachment; filename=series-" + seriesId + "-" + suffix + ".xml");
    SdmxDataWriter.write(message, format, Instant.now(), response.getWriter());
  }

  /** Published (Beta) observations of one series - current, or as of the given instant. */
  private List<Observation> seriesObservations(Long dataflowId, Long seriesId, Instant asOf) {
    return seriesObservations(dataflowId, seriesId, asOf, ObservationStage.BETA);
  }

  private List<Observation> seriesObservations(
      Long dataflowId, Long seriesId, Instant asOf, ObservationStage stage) {
    List<Observation> observations =
        asOf == null
            ? observationService.findCurrentForSeriesIds(dataflowId, stage, List.of(seriesId))
            : observationService.findAsOfForSeriesIds(dataflowId, stage, List.of(seriesId), asOf);
    return observations.stream().sorted(Comparator.comparing(Observation::timePeriod)).toList();
  }

  private List<Dataflow> searchDataflows(String q) {
    String needle = q.toLowerCase();
    return dataflowService.findAll().stream()
        .filter(
            df ->
                df.name().toLowerCase().contains(needle)
                    || df.artefactId().toLowerCase().contains(needle))
        .toList();
  }

  private Category findCategory(Long categoryId) {
    return categorySchemeService.findAll().stream()
        .flatMap(scheme -> scheme.categories().stream())
        .filter(c -> c.id().equals(categoryId))
        .findFirst()
        .orElseThrow();
  }

  private List<CategoryNode> buildTree(CategoryScheme scheme) {
    Map<Long, List<Category>> childrenByParent =
        scheme.categories().stream()
            .collect(
                Collectors.groupingBy(
                    c -> c.parentCategoryId() == null ? 0L : c.parentCategoryId()));
    return buildNodes(childrenByParent, 0L);
  }

  private List<CategoryNode> buildNodes(
      Map<Long, List<Category>> childrenByParent, Long parentKey) {
    return childrenByParent.getOrDefault(parentKey, List.of()).stream()
        .sorted(Comparator.comparing(Category::code))
        .map(
            c ->
                new CategoryNode(
                    c.id(),
                    c.code(),
                    c.name(),
                    buildNodes(childrenByParent, c.id()),
                    categorisationService.findByCategoryId(c.id()).size()))
        // children are already pruned, so an empty list means no dataflow anywhere below
        .filter(node -> node.dataflowCount() > 0 || !node.children().isEmpty())
        .toList();
  }

  private String csvEscape(String value) {
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }

  public record CategorySchemeTree(
      Long id, String artefactId, String name, boolean hasDescription, List<CategoryNode> roots) {}

  public record CategoryNode(
      Long id, String code, String name, List<CategoryNode> children, int dataflowCount) {}

  /**
   * @param pending Alpha view only: null = same as published, otherwise "new" or the published
   *     values the row would replace on promotion
   */
  public record ObsRow(
      String timePeriod,
      List<String> values,
      List<String> asOfValues,
      List<Revision> revisions,
      String pending) {
    public boolean revised() {
      return revisions.size() > 1;
    }
  }

  public record Revision(String values, String validFrom, String validTo, String comment) {}

  public record MeasureColumn(String header, String tooltip) {}

  public record DimensionInfo(
      String conceptCode,
      String conceptName,
      Long conceptSchemeId,
      String value,
      String valueName,
      Long codelistId,
      String codelistArtefactId) {}

  private record ConceptRef(String code, String name, Long schemeId, String schemeArtefactId) {
    String label() {
      return schemeArtefactId + ":" + code + " - " + name;
    }
  }
}
