package de.sdmxdw.web.explore;

import de.sdmxdw.microdata.FacetSlot;
import de.sdmxdw.microdata.FieldDefinition;
import de.sdmxdw.microdata.MicrodataCategorisationService;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataPayload;
import de.sdmxdw.microdata.MicrodataRecord;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.microdata.MicrodataStage;
import de.sdmxdw.structure.category.Category;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Read-only, Ökonom-facing browsing UI for microdata - same master-detail/htmx pattern as {@link
 * ExploreController}, but for microdata flows and their reference graph: clicking a reference field
 * swaps the same detail panel instead of navigating away, so a chain like Instrument -> Rahmen ->
 * Gegenpartei can be walked without losing context. Always browses Beta; Alpha stays on the
 * Datenmanager-facing /microdata admin pages.
 */
@Controller
@RequestMapping("/explore/microdata")
public class ExploreMicrodataController {

  private static final int PAGE_SIZE = 25;

  private final MicrodataFlowService microdataFlowService;
  private final MicrodataRecordService microdataRecordService;
  private final MicrodataCategorisationService microdataCategorisationService;
  private final CategorySchemeService categorySchemeService;

  public ExploreMicrodataController(
      MicrodataFlowService microdataFlowService,
      MicrodataRecordService microdataRecordService,
      MicrodataCategorisationService microdataCategorisationService,
      CategorySchemeService categorySchemeService) {
    this.microdataFlowService = microdataFlowService;
    this.microdataRecordService = microdataRecordService;
    this.microdataCategorisationService = microdataCategorisationService;
    this.categorySchemeService = categorySchemeService;
  }

  @GetMapping
  public String index(@RequestParam(required = false) String reportingPeriod, Model model) {
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
    List<MicrodataFlow> uncategorized =
        microdataFlowService.findAll().stream()
            .filter(f -> microdataCategorisationService.findByMicrodataFlowId(f.id()).isEmpty())
            .toList();
    model.addAttribute("trees", trees);
    model.addAttribute("uncategorizedFlows", uncategorized);
    model.addAttribute(
        "reportingPeriods",
        microdataRecordService.findDistinctReportingPeriods(MicrodataStage.BETA));
    model.addAttribute("reportingPeriod", reportingPeriod);
    return "explore/microdata-index";
  }

  @GetMapping("/categories/{categoryId}/flows-fragment")
  public String categoryFlowsFragment(
      @PathVariable Long categoryId,
      @RequestParam(required = false) String reportingPeriod,
      Model model) {
    List<MicrodataFlow> flows =
        microdataCategorisationService.findByCategoryId(categoryId).stream()
            .map(c -> microdataFlowService.findById(c.microdataFlowId()))
            .toList();
    model.addAttribute("flows", flows);
    model.addAttribute("reportingPeriod", reportingPeriod);
    return "explore/fragments/category-microdata-flows";
  }

  /**
   * Entity-relationship view of all microdata flows categorised anywhere in one category scheme:
   * flows become nodes, reference fields become edges to the flow they point at. Flows referenced
   * from outside the scheme are included too (flagged {@code external}) so no edge dangles.
   */
  @GetMapping("/category-schemes/{schemeId}/relations")
  @ResponseBody
  public RelationGraph relations(@PathVariable Long schemeId) {
    CategoryScheme scheme = categorySchemeService.findById(schemeId);
    Set<Long> schemeFlowIds = new LinkedHashSet<>();
    for (Category category : scheme.categories()) {
      microdataCategorisationService
          .findByCategoryId(category.id())
          .forEach(c -> schemeFlowIds.add(c.microdataFlowId()));
    }

    Map<Long, List<FieldDefinition>> fieldsByFlow = new LinkedHashMap<>();
    schemeFlowIds.forEach(id -> fieldsByFlow.put(id, microdataFlowService.findFields(id)));

    List<RelationEdge> edges = new ArrayList<>();
    Set<Long> allFlowIds = new LinkedHashSet<>(schemeFlowIds);
    fieldsByFlow.forEach(
        (flowId, fields) ->
            fields.stream()
                .filter(f -> f.referencedFlowId() != null)
                .forEach(
                    f -> {
                      edges.add(
                          new RelationEdge(
                              flowId, f.referencedFlowId(), f.fieldCode(), f.required()));
                      allFlowIds.add(f.referencedFlowId());
                    }));

    List<RelationNode> nodes = new ArrayList<>();
    for (Long flowId : allFlowIds) {
      MicrodataFlow flow = microdataFlowService.findById(flowId);
      List<FieldDefinition> fields =
          fieldsByFlow.getOrDefault(flowId, microdataFlowService.findFields(flowId));
      long requiredReferences =
          fields.stream().filter(f -> f.referencedFlowId() != null && f.required()).count();
      boolean referencedByOthers =
          edges.stream().anyMatch(e -> e.to().equals(flowId) && !e.from().equals(flowId));
      nodes.add(
          new RelationNode(
              flow.id(),
              flow.artefactId(),
              flow.name(),
              !schemeFlowIds.contains(flowId),
              requiredReferences >= 2 && !referencedByOthers,
              fields.stream()
                  .map(
                      f ->
                          new RelationField(
                              f.fieldCode(),
                              f.label(),
                              f.referencedFlowId() != null ? "REF" : f.dataType().name(),
                              f.required(),
                              f.groupCode(),
                              f.obligation(),
                              f.subtype(),
                              f.subtypeRule()))
                  .toList()));
    }
    return new RelationGraph(scheme.artefactId(), scheme.name(), nodes, edges);
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
                    microdataCategorisationService.findByCategoryId(c.id()).size()))
        // a category only belongs in the microdata tree if it (or a descendant) has flows -
        // children are already pruned at this point, so an empty children list means none did
        .filter(node -> node.flowCount() > 0 || !node.children().isEmpty())
        .toList();
  }

  @GetMapping("/{flowId}/records-fragment")
  public String recordsFragment(
      @PathVariable Long flowId,
      @RequestParam(required = false) String reportingPeriod,
      @RequestParam(required = false) String facet1,
      @RequestParam(required = false) String facet2,
      @RequestParam(required = false) String facet3,
      @RequestParam(required = false) String facet4,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      Model model) {
    Map<FacetSlot, FieldDefinition> fieldByFacetSlot = fieldsByFacetSlot(flowId);
    MicrodataRecordService.PageResult result =
        microdataRecordService.findCurrentFiltered(
            flowId,
            MicrodataStage.BETA,
            reportingPeriod,
            facet1,
            facet2,
            facet3,
            facet4,
            q,
            page,
            PAGE_SIZE);

    model.addAttribute("flowId", flowId);
    model.addAttribute("facet1Label", facetLabel(fieldByFacetSlot, FacetSlot.facet1));
    model.addAttribute("facet2Label", facetLabel(fieldByFacetSlot, FacetSlot.facet2));
    model.addAttribute("facet3Label", facetLabel(fieldByFacetSlot, FacetSlot.facet3));
    model.addAttribute("facet4Label", facetLabel(fieldByFacetSlot, FacetSlot.facet4));
    model.addAttribute("reportingPeriod", reportingPeriod);
    model.addAttribute("facet1", facet1);
    model.addAttribute("facet2", facet2);
    model.addAttribute("facet3", facet3);
    model.addAttribute("facet4", facet4);
    model.addAttribute("q", q);
    model.addAttribute("result", result);
    model.addAttribute("totalPages", (int) Math.ceil(result.totalCount() / (double) PAGE_SIZE));
    return "explore/fragments/microdata-records";
  }

  @GetMapping("/{flowId}/records/{recordId}/panel")
  public String panel(@PathVariable Long flowId, @PathVariable Long recordId, Model model) {
    MicrodataFlow flow = microdataFlowService.findById(flowId);
    MicrodataRecord record = microdataRecordService.findById(recordId);
    List<FieldDefinition> fields = microdataFlowService.findFields(flowId);
    MicrodataPayload payload = microdataRecordService.parsePayload(record.payloadJson());

    List<PanelRow> payloadRows = new ArrayList<>();
    Map<String, List<FieldDefinition>> fieldsByGroup = new LinkedHashMap<>();
    for (FieldDefinition field : fields) {
      if (field.groupCode() != null) {
        fieldsByGroup.computeIfAbsent(field.groupCode(), g -> new ArrayList<>()).add(field);
        continue;
      }
      payloadRows.add(
          buildRow(field, payload.scalars().getOrDefault(field.fieldCode(), ""), record));
    }

    List<PanelGroupSection> groupSections = new ArrayList<>();
    for (Map.Entry<String, List<FieldDefinition>> entry : fieldsByGroup.entrySet()) {
      List<List<PanelRow>> instances = new ArrayList<>();
      for (Map<String, String> instance :
          payload.groups().getOrDefault(entry.getKey(), List.of())) {
        List<PanelRow> rows = new ArrayList<>();
        for (FieldDefinition field : entry.getValue()) {
          rows.add(buildRow(field, instance.getOrDefault(field.fieldCode(), ""), record));
        }
        instances.add(rows);
      }
      groupSections.add(new PanelGroupSection(entry.getKey(), instances));
    }

    model.addAttribute("flow", flow);
    model.addAttribute("record", record);
    model.addAttribute("payloadRows", payloadRows);
    model.addAttribute("groupSections", groupSections);
    return "explore/fragments/microdata-panel";
  }

  private PanelRow buildRow(FieldDefinition field, String value, MicrodataRecord record) {
    if (field.referencedFlowId() == null || value == null || value.isBlank()) {
      return new PanelRow(field.label(), value, null, null);
    }
    return microdataRecordService
        .findCurrentOne(
            field.referencedFlowId(), value, record.reportingPeriod(), MicrodataStage.BETA)
        .map(target -> new PanelRow(field.label(), value, field.referencedFlowId(), target.id()))
        .orElse(new PanelRow(field.label(), value, null, null));
  }

  private String facetLabel(Map<FacetSlot, FieldDefinition> fieldByFacetSlot, FacetSlot slot) {
    FieldDefinition field = fieldByFacetSlot.get(slot);
    return field == null ? null : field.label();
  }

  private Map<FacetSlot, FieldDefinition> fieldsByFacetSlot(Long flowId) {
    Map<FacetSlot, FieldDefinition> result = new LinkedHashMap<>();
    for (FieldDefinition field : microdataFlowService.findFields(flowId)) {
      if (field.facetSlot() != null) {
        result.put(field.facetSlot(), field);
      }
    }
    return result;
  }

  public record PanelRow(String label, String value, Long targetFlowId, Long targetRecordId) {}

  public record PanelGroupSection(String groupCode, List<List<PanelRow>> instances) {}

  public record RelationGraph(
      String schemeArtefactId,
      String schemeName,
      List<RelationNode> nodes,
      List<RelationEdge> edges) {}

  /**
   * {@code linking} marks association entities: at least two mandatory references and not
   * referenced by any other entity themselves (e.g. EKE "Rahmen - Sicherheit").
   */
  public record RelationNode(
      Long id,
      String artefactId,
      String name,
      boolean external,
      boolean linking,
      List<RelationField> fields) {}

  public record RelationField(
      String code,
      String label,
      String type,
      boolean required,
      String groupCode,
      String obligation,
      String subtype,
      String subtypeRule) {}

  public record RelationEdge(Long from, Long to, String fieldCode, boolean required) {}

  public record CategorySchemeTree(
      Long id, String artefactId, String name, boolean hasDescription, List<CategoryNode> roots) {}

  public record CategoryNode(
      Long id, String code, String name, List<CategoryNode> children, int flowCount) {}
}
