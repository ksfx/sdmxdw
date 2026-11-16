package de.sdmxdw.web.explore;

import de.sdmxdw.data.observation.Observation;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.data.observation.ObservationStage;
import de.sdmxdw.data.series.Series;
import de.sdmxdw.structure.categorisation.CategorisationService;
import de.sdmxdw.structure.category.Category;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
import de.sdmxdw.structure.dsd.Measure;
import de.sdmxdw.web.AsOf;
import de.sdmxdw.web.ConceptLabels;
import de.sdmxdw.web.MarkdownRenderer;
import de.sdmxdw.web.rest.SdmxDataWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
  private final Breadcrumbs breadcrumbs;
  private final SeriesStructure structure;

  public ExploreController(
      CategorySchemeService categorySchemeService,
      CategorisationService categorisationService,
      DataflowService dataflowService,
      DataStructureDefinitionService dsdService,
      ConceptSchemeService conceptSchemeService,
      ObservationService observationService,
      CodelistService codelistService,
      MarkdownRenderer markdownRenderer,
      Breadcrumbs breadcrumbs,
      SeriesStructure structure) {
    this.breadcrumbs = breadcrumbs;
    this.structure = structure;
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
    model.addAttribute("crumbs", breadcrumbs.forCategory(categoryId));
    return "explore/category";
  }

  @GetMapping("/categories/{categoryId}/dataflows-fragment")
  public String categoryDataflowsFragment(@PathVariable Long categoryId, Model model) {
    model.addAttribute("dataflows", dataflowsForCategory(categoryId));
    return "explore/fragments/category-dataflows";
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
    Map<Long, SeriesStructure.ConceptRef> concepts = structure.conceptRefs();
    Series series = observationService.findSeriesById(seriesId);
    List<Measure> measures =
        dsd.measures().stream().sorted(Comparator.comparing(Measure::id)).toList();
    String timeDimensionCode =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() == DataType.DATE)
            .map(d -> concepts.get(d.conceptId()))
            .filter(java.util.Objects::nonNull)
            .map(SeriesStructure.ConceptRef::code)
            .findFirst()
            .orElse("TIME_PERIOD");

    List<String> dimensionIds = new ArrayList<>();
    List<String> keyValues = new ArrayList<>();
    structure
        .keyDimensionInfos(dsd, series.seriesKey(), concepts)
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
}
