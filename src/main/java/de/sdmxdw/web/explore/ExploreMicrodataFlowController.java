package de.sdmxdw.web.explore;

import de.sdmxdw.data.audit.AuditLogService;
import de.sdmxdw.microdata.FieldAttributes;
import de.sdmxdw.microdata.FieldDataType;
import de.sdmxdw.microdata.FieldDefinition;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataPayload;
import de.sdmxdw.microdata.MicrodataQuery;
import de.sdmxdw.microdata.MicrodataQueryRepository.PendingRecord;
import de.sdmxdw.microdata.MicrodataRecord;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.microdata.MicrodataStage;
import de.sdmxdw.structure.codelist.Code;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.web.MarkdownRenderer;
import de.sdmxdw.web.Roles;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The microdata flow page of the explorer, same pattern as the time series dataflow page: everyone
 * browses published (Beta) records; Datenmanager additionally get the stage switch, pending changes
 * (Alpha vs Beta), promotion, aggregation to SDMX, the activity log and the correction of records
 * (#16). The documentation tab shows the description of the flow and of every field with its codes
 * to everyone; Datenmanager edit it there (#23). The services enforce the role rules.
 */
@Controller
@RequestMapping("/explore/microdata/{flowId}")
public class ExploreMicrodataFlowController {

  private static final int PAGE_SIZE = 50;
  private static final int AUDIT_PAGE_SIZE = 20;

  /** Indexed (facet) fields are filtered with request parameters {@code f_<FIELD>}. */
  static final String FIELD_PARAM_PREFIX = "f_";

  enum View {
    records,
    documentation,
    pending,
    activity
  }

  private final MicrodataFlowService flowService;
  private final MicrodataRecordService recordService;
  private final CodelistService codelistService;
  private final AuditLogService auditLogService;
  private final MarkdownRenderer markdown;

  public ExploreMicrodataFlowController(
      MicrodataFlowService flowService,
      MicrodataRecordService recordService,
      CodelistService codelistService,
      AuditLogService auditLogService,
      MarkdownRenderer markdown) {
    this.flowService = flowService;
    this.recordService = recordService;
    this.codelistService = codelistService;
    this.auditLogService = auditLogService;
    this.markdown = markdown;
  }

  // --- flow page -------------------------------------------------------------------------------

  @GetMapping
  public String page(
      @PathVariable Long flowId,
      @RequestParam(required = false) String view,
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String reportingPeriod,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam MultiValueMap<String, String> params,
      Model model) {
    boolean dm = Roles.isDatenmanager();
    View selectedView = parseView(view, dm);
    MicrodataStage selectedStage = parseStage(stage, dm);
    MicrodataFlow flow = flowService.findById(flowId);
    List<FieldDefinition> fields = sortedFields(flowId);
    List<FieldDefinition> indexed = fields.stream().filter(f -> f.facetSlot() != null).toList();

    MultiValueMap<String, String> keep = new LinkedMultiValueMap<>(params);
    keep.remove("page");
    keep.remove("view");
    Map<String, String> tabUrls = new LinkedHashMap<>();
    for (View v : View.values()) {
      MultiValueMap<String, String> p = new LinkedMultiValueMap<>(keep);
      p.set("view", v.name());
      tabUrls.put(v.name(), url(flowId, p));
    }
    MultiValueMap<String, String> current = new LinkedMultiValueMap<>(keep);
    current.set("view", selectedView.name());
    String pageBase = url(flowId, current);
    MultiValueMap<String, String> otherStage = new LinkedMultiValueMap<>(current);
    otherStage.set("stage", selectedStage == MicrodataStage.BETA ? "ALPHA" : "BETA");

    MicrodataStage dataStage = selectedView == View.pending ? MicrodataStage.ALPHA : selectedStage;
    MultiValueMap<String, String> facetParams = new LinkedMultiValueMap<>();
    keep.forEach(
        (k, v) -> {
          if (k.startsWith(FIELD_PARAM_PREFIX) || k.equals("q") || k.equals("reportingPeriod")) {
            facetParams.put(k, v);
          }
        });

    model.addAttribute("flow", flow);
    model.addAttribute(
        "descriptionSummaryHtml", markdown.render(firstParagraph(flow.description())));
    model.addAttribute("isDatenmanager", dm);
    model.addAttribute("view", selectedView.name());
    model.addAttribute("stage", selectedStage.name());
    model.addAttribute("stageToggleUrl", url(flowId, otherStage));
    model.addAttribute("tabUrls", tabUrls);
    model.addAttribute("pageUrlPrefix", pageBase + (pageBase.contains("?") ? "&" : "?"));
    model.addAttribute("indexedFields", indexed);
    model.addAttribute("hasPickers", !indexed.isEmpty());
    model.addAttribute("selectedFieldParams", selectedFieldParams(indexed, params));
    model.addAttribute("reportingPeriods", recordService.findReportingPeriods(flowId, dataStage));
    model.addAttribute("reportingPeriod", reportingPeriod);
    model.addAttribute("query", q);
    model.addAttribute(
        "facetsUrl",
        UriComponentsBuilder.fromPath("/explore/microdata/{id}/facets")
            .queryParam("stage", dataStage.name())
            .buildAndExpand(flowId)
            .toUriString());
    model.addAttribute(
        "facetsQuery",
        UriComponentsBuilder.newInstance().queryParams(facetParams).build().encode().getQuery());
    model.addAttribute(
        "exportUrl",
        UriComponentsBuilder.fromPath("/rest/microdata/{ref}")
            .queryParam("reportingPeriod", reportingPeriod)
            .queryParam("stage", selectedStage == MicrodataStage.ALPHA ? "ALPHA" : null)
            .queryParams(restFilters(indexed, params))
            .buildAndExpand(flow.agencyId() + "," + flow.artefactId() + "," + flow.version())
            .encode()
            .toUriString());
    if (dm) {
      model.addAttribute("pendingCount", recordService.countPendingChanges(flowId));
    }

    switch (selectedView) {
      case records -> {
        MicrodataRecordService.RecordPage result =
            recordService.findRecordPage(
                query(flowId, selectedStage, reportingPeriod, q, indexed, params, page));
        model.addAttribute("result", result);
      }
      case pending -> {
        MicrodataRecordService.PendingPage result =
            recordService.findPendingChanges(
                query(flowId, MicrodataStage.ALPHA, reportingPeriod, q, indexed, params, page));
        model.addAttribute("result", result);
        model.addAttribute("rows", result.rows().stream().map(this::pendingRow).toList());
      }
      case activity ->
          model.addAttribute(
              "auditPage", auditLogService.findByMicrodataFlow(flowId, page, AUDIT_PAGE_SIZE));
      case documentation -> {
        model.addAttribute("descriptionHtml", markdown.render(flow.description()));
        model.addAttribute("fieldDocs", fields.stream().map(this::fieldDoc).toList());
      }
    }
    return "explore/microdata-flow";
  }

  @GetMapping(value = "/facets", produces = "application/json")
  @ResponseBody
  public Facets.Response facets(
      @PathVariable Long flowId,
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String reportingPeriod,
      @RequestParam(required = false) String q,
      @RequestParam MultiValueMap<String, String> params) {
    MicrodataStage facetStage = parseStage(stage, Roles.isDatenmanager());
    List<FieldDefinition> indexed =
        sortedFields(flowId).stream().filter(f -> f.facetSlot() != null).toList();
    MicrodataQuery query = query(flowId, facetStage, reportingPeriod, q, indexed, params, 0);
    Map<String, Map<String, Long>> counts = recordService.findFieldFacets(query);
    List<Facets.Dimension> dimensions = new ArrayList<>();
    for (FieldDefinition f : indexed) {
      Map<String, String> names = codeNames(f);
      Map<String, Long> values = new TreeMap<>(counts.getOrDefault(f.fieldCode(), Map.of()));
      List<String> selected = params.getOrDefault(FIELD_PARAM_PREFIX + f.fieldCode(), List.of());
      selected.forEach(v -> values.putIfAbsent(v, 0L));
      dimensions.add(
          new Facets.Dimension(
              FIELD_PARAM_PREFIX + f.fieldCode(),
              f.fieldCode(),
              f.label(),
              values.entrySet().stream()
                  .map(
                      e ->
                          new Facets.Value(
                              e.getKey(), names.getOrDefault(e.getKey(), ""), e.getValue()))
                  .toList(),
              selected.stream().sorted().toList()));
    }
    return new Facets.Response(recordService.countRecords(query), dimensions);
  }

  @PostMapping("/promote")
  public String promote(
      @PathVariable Long flowId, Authentication authentication, RedirectAttributes redirect) {
    MicrodataRecordService.PromotionResult result =
        recordService.promoteToBeta(flowId, authentication.getName());
    redirect.addFlashAttribute(
        "flash",
        result.promoted()
            + " record(s) published to Beta"
            + (result.unchanged() > 0 ? ", " + result.unchanged() + " unchanged" : "")
            + ".");
    return "redirect:/explore/microdata/" + flowId + "?view=pending";
  }

  @PostMapping("/aggregate")
  public String aggregate(
      @PathVariable Long flowId, Authentication authentication, RedirectAttributes redirect) {
    MicrodataRecordService.AggregationResult result =
        recordService.aggregateAndPublish(flowId, authentication.getName());
    redirect.addFlashAttribute(
        "flash",
        "Aggregated: "
            + result.groupsWritten()
            + " group(s) written, "
            + result.promoted()
            + " observation(s) published to the linked dataflow.");
    return "redirect:/explore/microdata/" + flowId + "?view=activity";
  }

  // --- documentation (#23)
  // ------------------------------------------------------------------------

  @PostMapping("/documentation")
  public String describeFlow(
      @PathVariable Long flowId,
      @RequestParam(required = false) String description,
      Authentication authentication,
      RedirectAttributes redirect) {
    flowService.describeFlow(flowId, description, authentication.getName());
    redirect.addFlashAttribute("flash", "Description of the flow saved.");
    return "redirect:/explore/microdata/" + flowId + "?view=documentation";
  }

  @PostMapping("/fields/{fieldId}/documentation")
  public String describeField(
      @PathVariable Long flowId,
      @PathVariable Long fieldId,
      @RequestParam(required = false) String description,
      @RequestParam(required = false) String attributes,
      Authentication authentication,
      RedirectAttributes redirect) {
    FieldDefinition field;
    try {
      field =
          flowService.describeField(
              flowId,
              fieldId,
              description,
              FieldAttributes.parseLines(attributes),
              authentication.getName());
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("error", e.getMessage());
      return "redirect:/explore/microdata/" + flowId + "?view=documentation";
    }
    redirect.addFlashAttribute("flash", "Documentation of " + field.fieldCode() + " saved.");
    return "redirect:/explore/microdata/"
        + flowId
        + "?view=documentation#field-"
        + field.fieldCode();
  }

  private FieldDoc fieldDoc(FieldDefinition f) {
    Codelist codelist = f.codelistId() == null ? null : codelistService.findById(f.codelistId());
    MicrodataFlow target =
        f.referencedFlowId() == null ? null : flowService.findById(f.referencedFlowId());
    Map<String, String> attributes = FieldAttributes.fromJson(f.attributes());
    return new FieldDoc(
        f,
        markdown.render(f.description()),
        f.description() == null ? "" : f.description(),
        attributes,
        FieldAttributes.toLines(attributes),
        codelist,
        codelist == null
            ? List.of()
            : codelist.codes().stream().sorted(Comparator.comparing(Code::code)).toList(),
        target);
  }

  /**
   * Documentation of one field for the documentation tab.
   *
   * @param descriptionHtml rendered Markdown definition
   * @param descriptionSource Markdown for the edit form
   * @param attributesText attributes as "Key: Value" lines for the edit form
   */
  public record FieldDoc(
      FieldDefinition field,
      String descriptionHtml,
      String descriptionSource,
      Map<String, String> attributes,
      String attributesText,
      Codelist codelist,
      List<Code> codes,
      MicrodataFlow referencedFlow) {}

  /** First paragraph of a Markdown text (the summary shown in the page header). */
  static String firstParagraph(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String trimmed = text.strip();
    int end = trimmed.indexOf("\n\n");
    return end < 0 ? trimmed : trimmed.substring(0, end);
  }

  /** A full stop before a capital letter, but not after abbreviations like "Art." or "z.B.". */
  private static final Pattern SENTENCE_END =
      Pattern.compile(
          "(?<!\\b(?:Art|Abs|Ziff|Nr|Kap|bzw|vgl|ca|Bst|lit|al|bspw|inkl|z\\.B|z\\. B))\\.\\s+(?=[A-ZÄÖÜ])");

  /** First sentence of a Markdown definition as plain text, for tooltips. */
  static String summary(String description) {
    String paragraph = firstParagraph(description);
    if (paragraph == null) {
      return null;
    }
    String plain = paragraph.replaceAll("[*_`#>|]", "").replaceAll("\\s+", " ").strip();
    Matcher end = SENTENCE_END.matcher(plain);
    String sentence = end.find() ? plain.substring(0, end.start() + 1) : plain;
    return sentence.length() > 240 ? sentence.substring(0, 237) + "..." : sentence;
  }

  // --- record page, history and correction (#16) -------------------------------------------------

  @GetMapping("/records/{recordId}")
  public String record(@PathVariable Long flowId, @PathVariable Long recordId, Model model) {
    MicrodataRecord record = loadRecord(flowId, recordId);
    boolean dm = Roles.isDatenmanager();
    List<FieldDefinition> fields = sortedFields(flowId);
    MicrodataPayload payload = recordService.parsePayload(record.payloadJson());
    boolean currentAlpha = record.stage() == MicrodataStage.ALPHA && record.validTo() == null;

    // current Alpha: compare with what is published
    MicrodataPayload published = null;
    boolean unpublished = false;
    if (currentAlpha) {
      Optional<MicrodataRecord> beta =
          recordService.findCurrentOne(
              flowId, record.entityId(), record.reportingPeriod(), MicrodataStage.BETA);
      published = beta.map(b -> recordService.parsePayload(b.payloadJson())).orElse(null);
      unpublished = beta.isEmpty();
    }
    List<FieldRow> rows = new ArrayList<>();
    for (FieldDefinition f : fields) {
      if (f.groupCode() != null) {
        continue;
      }
      String value = payload.scalars().get(f.fieldCode());
      String publishedValue = published == null ? null : published.scalars().get(f.fieldCode());
      boolean changed =
          published != null && !java.util.Objects.equals(blank(value), blank(publishedValue));
      rows.add(
          new FieldRow(
              f.fieldCode(),
              f.label(),
              summary(f.description()),
              value,
              codeNames(f).get(value),
              referenceLink(f, value, record),
              changed,
              publishedValue));
    }

    List<MicrodataRecord> history =
        recordService.findHistory(
            flowId, record.entityId(), record.reportingPeriod(), record.stage());
    Optional<MicrodataRecord> otherStage =
        dm
            ? recordService.findCurrentOne(
                flowId,
                record.entityId(),
                record.reportingPeriod(),
                record.stage() == MicrodataStage.BETA ? MicrodataStage.ALPHA : MicrodataStage.BETA)
            : Optional.empty();

    model.addAttribute("flow", flowService.findById(flowId));
    model.addAttribute("record", record);
    model.addAttribute("rows", rows);
    model.addAttribute("groups", groupSections(fields, payload, published));
    model.addAttribute("history", history);
    model.addAttribute("isDatenmanager", dm);
    model.addAttribute("canEdit", dm && currentAlpha);
    model.addAttribute("unpublished", unpublished);
    model.addAttribute("otherStageRecordId", otherStage.map(MicrodataRecord::id).orElse(null));
    return "explore/microdata-record";
  }

  @GetMapping("/records/{recordId}/edit")
  public String editForm(@PathVariable Long flowId, @PathVariable Long recordId, Model model) {
    MicrodataRecord record = loadRecord(flowId, recordId);
    MicrodataPayload payload = recordService.parsePayload(record.payloadJson());
    editModel(model, flowId, record, payload.scalars(), payload.groups(), null, null);
    return "explore/microdata-record-edit";
  }

  @PostMapping("/records/{recordId}/correct")
  public String correct(
      @PathVariable Long flowId,
      @PathVariable Long recordId,
      @RequestParam(required = false) String comment,
      @RequestParam Map<String, String> params,
      Authentication authentication,
      Model model,
      RedirectAttributes redirect) {
    MicrodataRecord record = loadRecord(flowId, recordId);
    List<FieldDefinition> fields = sortedFields(flowId);
    Map<String, String> scalars = new LinkedHashMap<>();
    Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
    parseForm(fields, params, scalars, groups);
    try {
      MicrodataRecord saved =
          recordService.correctRecord(
              flowId,
              record.entityId(),
              record.reportingPeriod(),
              scalars,
              groups,
              comment,
              authentication.getName());
      redirect.addFlashAttribute("flash", "Saved in Alpha - published with the next promotion.");
      return "redirect:/explore/microdata/" + flowId + "/records/" + saved.id();
    } catch (IllegalArgumentException e) {
      // same validation as on ingest: show the form again with the entered values
      editModel(model, flowId, record, scalars, groups, comment, e.getMessage());
      return "explore/microdata-record-edit";
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  private MicrodataRecord loadRecord(Long flowId, Long recordId) {
    MicrodataRecord record = recordService.findById(recordId);
    if (!record.microdataFlowId().equals(flowId)) {
      throw new IllegalArgumentException("Record " + recordId + " is not part of flow " + flowId);
    }
    if (record.stage() == MicrodataStage.ALPHA && !Roles.isDatenmanager()) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Unpublished records are only visible to Datenmanager");
    }
    return record;
  }

  private void editModel(
      Model model,
      Long flowId,
      MicrodataRecord record,
      Map<String, String> scalars,
      Map<String, List<Map<String, String>>> groups,
      String comment,
      String error) {
    List<FieldDefinition> fields = sortedFields(flowId);
    List<EditField> scalarFields = new ArrayList<>();
    Map<String, List<EditField>> groupFields = new LinkedHashMap<>();
    for (FieldDefinition f : fields) {
      EditField ef = editField(f);
      if (f.groupCode() == null) {
        scalarFields.add(ef.withValue(scalars.get(f.fieldCode())));
      } else {
        groupFields.computeIfAbsent(f.groupCode(), g -> new ArrayList<>()).add(ef);
      }
    }
    List<EditGroup> editGroups = new ArrayList<>();
    groupFields.forEach(
        (code, gf) -> {
          List<Map<String, String>> items = new ArrayList<>(groups.getOrDefault(code, List.of()));
          // two blank rows to add entries; blank rows are ignored on save
          items.add(Map.of());
          items.add(Map.of());
          editGroups.add(new EditGroup(code, gf, items));
        });
    model.addAttribute("flow", flowService.findById(flowId));
    model.addAttribute("record", record);
    model.addAttribute("fields", scalarFields);
    model.addAttribute("groups", editGroups);
    model.addAttribute("comment", comment);
    model.addAttribute("error", error);
  }

  private EditField editField(FieldDefinition f) {
    List<Facets.Value> options =
        codeNames(f).entrySet().stream()
            .map(e -> new Facets.Value(e.getKey(), e.getValue(), 0))
            .toList();
    String inputType =
        switch (f.dataType()) {
          case DATE -> "date";
          case NUMBER -> "number";
          default -> "text";
        };
    return new EditField(
        f.fieldCode(),
        f.label(),
        f.dataType(),
        inputType,
        f.required(),
        options,
        f.referencedFlowId() == null
            ? null
            : flowService.findById(f.referencedFlowId()).artefactId(),
        null);
  }

  /** Form fields: {@code v.<FIELD>} for scalars, {@code g.<GROUP>.<n>.<FIELD>} for groups. */
  private static void parseForm(
      List<FieldDefinition> fields,
      Map<String, String> params,
      Map<String, String> scalars,
      Map<String, List<Map<String, String>>> groups) {
    Map<String, TreeMap<Integer, Map<String, String>>> items = new LinkedHashMap<>();
    for (FieldDefinition f : fields) {
      if (f.groupCode() == null) {
        scalars.put(f.fieldCode(), blank(params.get("v." + f.fieldCode())));
      } else {
        items.computeIfAbsent(f.groupCode(), g -> new TreeMap<>());
      }
    }
    params.forEach(
        (name, value) -> {
          if (!name.startsWith("g.")) {
            return;
          }
          String[] parts = name.split("\\.", 4);
          if (parts.length != 4 || !items.containsKey(parts[1])) {
            return;
          }
          int index;
          try {
            index = Integer.parseInt(parts[2]);
          } catch (NumberFormatException e) {
            return;
          }
          items
              .get(parts[1])
              .computeIfAbsent(index, i -> new LinkedHashMap<>())
              .put(parts[3], value);
        });
    items.forEach(
        (group, byIndex) -> {
          List<Map<String, String>> list = new ArrayList<>();
          for (Map<String, String> item : byIndex.values()) {
            if ("on".equals(item.remove("_remove"))) {
              continue;
            }
            Map<String, String> cleaned = new LinkedHashMap<>();
            item.forEach((k, v) -> cleaned.put(k, blank(v)));
            if (cleaned.values().stream().anyMatch(v -> v != null)) {
              list.add(cleaned);
            }
          }
          groups.put(group, list);
        });
  }

  private MicrodataQuery query(
      Long flowId,
      MicrodataStage stage,
      String reportingPeriod,
      String q,
      List<FieldDefinition> indexed,
      MultiValueMap<String, String> params,
      int page) {
    Map<String, List<String>> filters = new LinkedHashMap<>();
    for (FieldDefinition f : indexed) {
      List<String> values =
          params.getOrDefault(FIELD_PARAM_PREFIX + f.fieldCode(), List.of()).stream()
              .filter(v -> !v.isBlank())
              .toList();
      if (!values.isEmpty()) {
        filters.put(f.fieldCode(), values);
      }
    }
    return new MicrodataQuery(
        flowId,
        stage,
        reportingPeriod == null || reportingPeriod.isBlank() ? List.of() : List.of(reportingPeriod),
        null,
        filters,
        q == null || q.isBlank() ? null : q,
        PAGE_SIZE,
        Math.max(page, 0) * PAGE_SIZE);
  }

  private static MultiValueMap<String, String> selectedFieldParams(
      List<FieldDefinition> indexed, MultiValueMap<String, String> params) {
    MultiValueMap<String, String> selected = new LinkedMultiValueMap<>();
    indexed.forEach(
        f -> {
          List<String> v = params.get(FIELD_PARAM_PREFIX + f.fieldCode());
          if (v != null) {
            selected.put(FIELD_PARAM_PREFIX + f.fieldCode(), v);
          }
        });
    return selected;
  }

  /** The same filters in the REST API's syntax, for the export link. */
  private static MultiValueMap<String, String> restFilters(
      List<FieldDefinition> indexed, MultiValueMap<String, String> params) {
    MultiValueMap<String, String> rest = new LinkedMultiValueMap<>();
    indexed.forEach(
        f -> {
          List<String> v = params.get(FIELD_PARAM_PREFIX + f.fieldCode());
          if (v != null && !v.isEmpty()) {
            rest.add("c[" + f.fieldCode() + "]", String.join(",", v));
          }
        });
    return rest;
  }

  private PendingRow pendingRow(PendingRecord p) {
    MicrodataRecord a = p.alpha();
    return new PendingRow(
        a.id(),
        a.entityId(),
        a.reportingPeriod(),
        p.betaPayloadJson() == null
            ? null
            : recordService.describeChanges(
                recordService.parsePayload(p.betaPayloadJson()),
                recordService.parsePayload(a.payloadJson())),
        a.validFrom().toString(),
        a.recordedBy(),
        a.comment());
  }

  private List<GroupSection> groupSections(
      List<FieldDefinition> fields, MicrodataPayload payload, MicrodataPayload published) {
    Map<String, List<FieldDefinition>> byGroup = new LinkedHashMap<>();
    fields.stream()
        .filter(f -> f.groupCode() != null)
        .forEach(f -> byGroup.computeIfAbsent(f.groupCode(), g -> new ArrayList<>()).add(f));
    List<GroupSection> sections = new ArrayList<>();
    byGroup.forEach(
        (code, gf) -> {
          List<Map<String, String>> items = payload.groups().getOrDefault(code, List.of());
          boolean changed =
              published != null && !items.equals(published.groups().getOrDefault(code, List.of()));
          sections.add(new GroupSection(code, gf, items, changed));
        });
    return sections;
  }

  private String referenceLink(FieldDefinition f, String value, MicrodataRecord record) {
    if (f.referencedFlowId() == null || value == null || value.isBlank()) {
      return null;
    }
    return recordService
        .findCurrentOne(f.referencedFlowId(), value, record.reportingPeriod(), record.stage())
        .map(t -> "/explore/microdata/" + f.referencedFlowId() + "/records/" + t.id())
        .orElse(null);
  }

  private Map<String, String> codeNames(FieldDefinition f) {
    Map<String, String> names = new LinkedHashMap<>();
    if (f.codelistId() != null) {
      codelistService.findById(f.codelistId()).codes().stream()
          .sorted(Comparator.comparing(Code::code))
          .forEach(c -> names.put(c.code(), c.name()));
    }
    return names;
  }

  private List<FieldDefinition> sortedFields(Long flowId) {
    return flowService.findFields(flowId).stream()
        .sorted(Comparator.comparingInt(FieldDefinition::position))
        .toList();
  }

  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static View parseView(String view, boolean dm) {
    View v;
    try {
      v = view == null ? View.records : View.valueOf(view);
    } catch (IllegalArgumentException e) {
      v = View.records;
    }
    return !dm && v != View.records && v != View.documentation ? View.records : v;
  }

  static MicrodataStage parseStage(String stage, boolean dm) {
    return dm && "ALPHA".equalsIgnoreCase(stage) ? MicrodataStage.ALPHA : MicrodataStage.BETA;
  }

  private static String url(Long flowId, MultiValueMap<String, String> params) {
    return UriComponentsBuilder.fromPath("/explore/microdata/{id}")
        .queryParams(params)
        .buildAndExpand(flowId)
        .encode()
        .toUriString();
  }

  // --- view models -------------------------------------------------------------------------------

  /**
   * @param link explorer URL of the referenced record, if the field is a reference
   * @param changed current Alpha only: differs from the published value
   */
  public record FieldRow(
      String code,
      String label,
      String summary,
      String value,
      String valueName,
      String link,
      boolean changed,
      String publishedValue) {}

  public record GroupSection(
      String code,
      List<FieldDefinition> fields,
      List<Map<String, String>> items,
      boolean changed) {}

  /**
   * @param changes null = new record, never published
   */
  public record PendingRow(
      Long recordId,
      String entityId,
      String reportingPeriod,
      String changes,
      String validFrom,
      String recordedBy,
      String comment) {}

  public record EditField(
      String code,
      String label,
      FieldDataType dataType,
      String inputType,
      boolean required,
      List<Facets.Value> options,
      String referencedFlow,
      String value) {
    EditField withValue(String newValue) {
      return new EditField(
          code, label, dataType, inputType, required, options, referencedFlow, newValue);
    }
  }

  public record EditGroup(String code, List<EditField> fields, List<Map<String, String>> items) {}
}
