package de.sdmxdw.web.rest;

import de.sdmxdw.microdata.FieldAttributes;
import de.sdmxdw.microdata.FieldDataType;
import de.sdmxdw.microdata.FieldDefinition;
import de.sdmxdw.microdata.MicrodataCategorisationService;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataPayload;
import de.sdmxdw.microdata.MicrodataQuery;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.microdata.MicrodataStage;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.web.AsOf;
import de.sdmxdw.web.rest.MicrodataTableWriter.Column;
import de.sdmxdw.web.rest.MicrodataTableWriter.Format;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * REST API for microdata, following the conventions of the SDMX data API where they fit.
 *
 * <ul>
 *   <li>{@code GET /rest/microdata} - catalog of microdata flows
 *   <li>{@code GET /rest/microdata/{flowRef}/structure} - field catalog, repeating groups,
 *       codelists and relations to other flows (JSON)
 *   <li>{@code GET /rest/microdata/{flowRef}} - one row per entity and reporting period
 *   <li>{@code GET /rest/microdata/{flowRef}/groups/{group}} - repeating group as child table
 *       (ENTITY_ID, REPORTING_PERIOD, ITEM_NO + group fields)
 * </ul>
 *
 * Data parameters: reportingPeriod (comma-separated or "latest"), asOf, stage (BETA default, ALPHA
 * for Datenmanager), c[FIELD]=A,B filters on scalar fields, limit/offset (records), format csv
 * (SDMX-CSV, default) | json | parquet. Reference fields are delivered as ids, not pre-joined: the
 * structure endpoint tells which flow and key to join on.
 */
@RestController
@RequestMapping("/rest/microdata")
public class MicrodataRestController {

  private final MicrodataFlowService flowService;
  private final MicrodataRecordService recordService;
  private final MicrodataCategorisationService categorisationService;
  private final CategorySchemeService categorySchemeService;
  private final CodelistService codelistService;
  private final ObjectMapper objectMapper;

  public MicrodataRestController(
      MicrodataFlowService flowService,
      MicrodataRecordService recordService,
      MicrodataCategorisationService categorisationService,
      CategorySchemeService categorySchemeService,
      CodelistService codelistService,
      ObjectMapper objectMapper) {
    this.flowService = flowService;
    this.recordService = recordService;
    this.categorisationService = categorisationService;
    this.categorySchemeService = categorySchemeService;
    this.codelistService = codelistService;
    this.objectMapper = objectMapper;
  }

  @GetMapping(produces = "application/json")
  public List<Map<String, Object>> catalog(HttpServletRequest request) {
    Map<Long, String[]> categoryByCategoryId = new LinkedHashMap<>();
    for (CategoryScheme scheme : categorySchemeService.findAll()) {
      scheme
          .categories()
          .forEach(
              c ->
                  categoryByCategoryId.put(
                      c.id(),
                      new String[] {
                        scheme.agencyId()
                            + ":"
                            + scheme.artefactId()
                            + "("
                            + scheme.version()
                            + ")",
                        c.code()
                      }));
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (MicrodataFlow flow :
        flowService.findAll().stream()
            .sorted(
                Comparator.comparing(MicrodataFlow::agencyId)
                    .thenComparing(MicrodataFlow::artefactId))
            .toList()) {
      Map<String, Object> entry = flowInfo(flow);
      categorisationService
          .findByMicrodataFlowId(flow.id())
          .map(c -> categoryByCategoryId.get(c.categoryId()))
          .ifPresent(c -> entry.put("category", Map.of("categoryScheme", c[0], "category", c[1])));
      entry.put("links", links(flow, request));
      result.add(entry);
    }
    return result;
  }

  @GetMapping(value = "/{flowRef}/structure", produces = "application/json")
  public Map<String, Object> structure(
      @PathVariable String flowRef,
      @RequestParam(required = false) String stage,
      HttpServletRequest request) {
    MicrodataFlow flow = resolve(flowRef);
    List<FieldDefinition> fields = sortedFields(flow.id());
    List<MicrodataFlow> allFlows = flowService.findAll();
    Map<Long, MicrodataFlow> flowsById = new LinkedHashMap<>();
    allFlows.forEach(f -> flowsById.put(f.id(), f));

    Map<String, Object> result = flowInfo(flow);
    result.put(
        "reportingPeriods", recordService.findReportingPeriods(flow.id(), parseStage(stage)));
    result.put("primaryKey", List.of("ENTITY_ID", "REPORTING_PERIOD"));
    result.put(
        "fields",
        fields.stream()
            .filter(f -> f.groupCode() == null)
            .map(f -> fieldInfo(f, flowsById))
            .toList());

    Map<String, List<FieldDefinition>> groups = groups(fields);
    List<Map<String, Object>> groupInfo = new ArrayList<>();
    groups.forEach(
        (code, groupFields) -> {
          Map<String, Object> g = new LinkedHashMap<>();
          g.put("code", code);
          g.put("primaryKey", List.of("ENTITY_ID", "REPORTING_PERIOD", "ITEM_NO"));
          g.put("parentKey", List.of("ENTITY_ID", "REPORTING_PERIOD"));
          g.put("data", base(request) + "/rest/microdata/" + ref(flow) + "/groups/" + code);
          g.put("fields", groupFields.stream().map(f -> fieldInfo(f, flowsById)).toList());
          groupInfo.add(g);
        });
    result.put("groups", groupInfo);

    Map<String, Object> codelists = new TreeMap<>();
    fields.stream()
        .map(FieldDefinition::codelistId)
        .filter(Objects::nonNull)
        .distinct()
        .forEach(
            id -> {
              Codelist cl = codelistService.findById(id);
              Map<String, String> codes = new TreeMap<>();
              cl.codes().forEach(c -> codes.put(c.code(), c.name()));
              codelists.put(codelistRef(cl), Map.of("name", cl.name(), "codes", codes));
            });
    result.put("codelists", codelists);

    List<Map<String, Object>> incoming = new ArrayList<>();
    for (MicrodataFlow other : allFlows) {
      for (FieldDefinition f : flowService.findFields(other.id())) {
        if (flow.id().equals(f.referencedFlowId())) {
          incoming.add(
              Map.of(
                  "flow", ref(other),
                  "field", f.fieldCode(),
                  "joinOn",
                      Map.of(f.fieldCode(), "ENTITY_ID", "REPORTING_PERIOD", "REPORTING_PERIOD")));
        }
      }
    }
    result.put("referencedBy", incoming);
    result.put("links", links(flow, request));
    return result;
  }

  @GetMapping("/{flowRef}")
  public void data(
      @PathVariable String flowRef,
      @RequestParam Map<String, String> params,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    MicrodataFlow flow = resolve(flowRef);
    List<FieldDefinition> scalarFields =
        sortedFields(flow.id()).stream().filter(f -> f.groupCode() == null).toList();
    List<Column> columns = new ArrayList<>();
    columns.add(new Column("ENTITY_ID", FieldDataType.STRING));
    columns.add(new Column("REPORTING_PERIOD", FieldDataType.STRING));
    scalarFields.forEach(f -> columns.add(new Column(f.fieldCode(), f.dataType())));
    write(
        flow,
        null,
        columns,
        params,
        accept,
        request,
        response,
        (record, payload, writer) -> {
          List<String> values = new ArrayList<>(columns.size());
          values.add(record.entityId());
          values.add(record.reportingPeriod());
          scalarFields.forEach(f -> values.add(payload.scalars().get(f.fieldCode())));
          writer.row(values);
        });
  }

  @GetMapping("/{flowRef}/groups/{groupCode}")
  public void groupData(
      @PathVariable String flowRef,
      @PathVariable String groupCode,
      @RequestParam Map<String, String> params,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    MicrodataFlow flow = resolve(flowRef);
    List<FieldDefinition> groupFields = groups(sortedFields(flow.id())).get(groupCode);
    if (groupFields == null) {
      throw new NoSuchElementException("Flow " + ref(flow) + " has no group " + groupCode);
    }
    List<Column> columns = new ArrayList<>();
    columns.add(new Column("ENTITY_ID", FieldDataType.STRING));
    columns.add(new Column("REPORTING_PERIOD", FieldDataType.STRING));
    columns.add(new Column("ITEM_NO", FieldDataType.NUMBER));
    groupFields.forEach(f -> columns.add(new Column(f.fieldCode(), f.dataType())));
    write(
        flow,
        groupCode,
        columns,
        params,
        accept,
        request,
        response,
        (record, payload, writer) -> {
          List<Map<String, String>> items = payload.groups().getOrDefault(groupCode, List.of());
          for (int i = 0; i < items.size(); i++) {
            List<String> values = new ArrayList<>(columns.size());
            values.add(record.entityId());
            values.add(record.reportingPeriod());
            values.add(String.valueOf(i + 1));
            Map<String, String> item = items.get(i);
            groupFields.forEach(f -> values.add(item.get(f.fieldCode())));
            writer.row(values);
          }
        });
  }

  private interface RowEmitter {
    void emit(
        de.sdmxdw.microdata.MicrodataRecord record,
        MicrodataPayload payload,
        MicrodataTableWriter writer);
  }

  private void write(
      MicrodataFlow flow,
      String groupCode,
      List<Column> columns,
      Map<String, String> params,
      String accept,
      HttpServletRequest request,
      HttpServletResponse response,
      RowEmitter emitter)
      throws IOException {
    Format format = resolveFormat(params.get("format"), accept);
    MicrodataQuery query = query(flow, params);
    long total = recordService.countRecords(query);
    response.setHeader("X-Total-Count", String.valueOf(total));
    if (query.limit() != null && query.offset() + query.limit() < total) {
      String next =
          request.getRequestURL()
              + "?"
              + withParam(request.getQueryString(), "offset", query.offset() + query.limit());
      response.setHeader(HttpHeaders.LINK, "<" + next + ">; rel=\"next\"");
    }
    response.setContentType(format.mediaType);
    String fileName =
        flow.artefactId() + (groupCode == null ? "" : "_" + groupCode) + "." + format.extension;
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        (format == Format.PARQUET ? "attachment" : "inline") + "; filename=\"" + fileName + "\"");
    String structureId =
        flow.agencyId()
            + ":"
            + flow.artefactId()
            + "("
            + flow.version()
            + ")"
            + (groupCode == null ? "" : "/" + groupCode);
    try (MicrodataTableWriter writer =
        MicrodataTableWriter.create(
            format, columns, structureId, objectMapper, response.getOutputStream())) {
      recordService.streamRecords(
          query,
          record -> emitter.emit(record, recordService.parsePayload(record.payloadJson()), writer));
    }
  }

  private MicrodataQuery query(MicrodataFlow flow, Map<String, String> params) {
    MicrodataStage stage = parseStage(params.get("stage"));
    List<String> periods = new ArrayList<>();
    String reportingPeriod = params.get("reportingPeriod");
    if (reportingPeriod != null && !reportingPeriod.isBlank()) {
      for (String p : reportingPeriod.split("[,+ ]")) {
        if (p.equalsIgnoreCase("latest")) {
          List<String> available = recordService.findReportingPeriods(flow.id(), stage);
          if (!available.isEmpty()) {
            periods.add(available.getLast());
          }
        } else if (!p.isBlank()) {
          periods.add(p);
        }
      }
      if (periods.isEmpty()) {
        throw new NoSuchElementException("No reporting period available for " + ref(flow));
      }
    }
    Map<String, FieldDefinition> scalarFields = new LinkedHashMap<>();
    flowService.findFields(flow.id()).stream()
        .filter(f -> f.groupCode() == null)
        .forEach(f -> scalarFields.put(f.fieldCode(), f));
    Map<String, List<String>> filters = new LinkedHashMap<>();
    params.forEach(
        (name, value) -> {
          if (!name.startsWith("c[") || !name.endsWith("]")) {
            return;
          }
          String field = name.substring(2, name.length() - 1);
          if (!scalarFields.containsKey(field)) {
            throw new IllegalArgumentException(
                "Unknown field in filter: " + field + " (only non-group fields can be filtered)");
          }
          filters.put(
              field,
              Arrays.stream(value.split(","))
                  .map(v -> v.startsWith("eq:") ? v.substring(3) : v)
                  .filter(v -> !v.isEmpty())
                  .toList());
        });
    return new MicrodataQuery(
        flow.id(),
        stage,
        periods,
        AsOf.parse(params.get("asOf")),
        filters,
        intParam(params.get("limit"), "limit"),
        Objects.requireNonNullElse(intParam(params.get("offset"), "offset"), 0));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private MicrodataFlow resolve(String flowRef) {
    String[] ref = SdmxRestSupport.parseFlowRef(flowRef);
    String agency = SdmxRestSupport.INSTANCE_AGENCY.equalsIgnoreCase(ref[0]) ? "all" : ref[0];
    List<MicrodataFlow> matches =
        flowService.findAll().stream()
            .filter(f -> SdmxRestSupport.isWildcard(agency) || f.agencyId().equals(agency))
            .filter(f -> f.artefactId().equals(ref[1]))
            .filter(f -> SdmxRestSupport.isWildcard(ref[2]) || f.version().equals(ref[2]))
            .sorted((a, b) -> SdmxRestSupport.compareVersions(b.version(), a.version()))
            .toList();
    if (matches.isEmpty()) {
      throw new NoSuchElementException("Unknown microdata flow: " + flowRef);
    }
    if (matches.stream().map(MicrodataFlow::agencyId).distinct().count() > 1) {
      throw new IllegalArgumentException(
          "Microdata flow id "
              + ref[1]
              + " is ambiguous, specify the agency (AGENCY,"
              + ref[1]
              + ")");
    }
    return matches.getFirst();
  }

  private List<FieldDefinition> sortedFields(Long flowId) {
    return flowService.findFields(flowId).stream()
        .sorted(Comparator.comparingInt(FieldDefinition::position))
        .toList();
  }

  private static Map<String, List<FieldDefinition>> groups(List<FieldDefinition> fields) {
    Map<String, List<FieldDefinition>> groups = new LinkedHashMap<>();
    fields.stream()
        .filter(f -> f.groupCode() != null)
        .forEach(f -> groups.computeIfAbsent(f.groupCode(), g -> new ArrayList<>()).add(f));
    return groups;
  }

  private Map<String, Object> fieldInfo(FieldDefinition f, Map<Long, MicrodataFlow> flowsById) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("code", f.fieldCode());
    info.put("label", f.label());
    info.put("dataType", f.dataType().name());
    info.put("required", f.required());
    putIfPresent(info, "obligation", f.obligation());
    putIfPresent(info, "subtype", f.subtype());
    putIfPresent(info, "subtypeRule", f.subtypeRule());
    putIfPresent(info, "description", f.description());
    Map<String, String> attributes = FieldAttributes.fromJson(f.attributes());
    if (!attributes.isEmpty()) {
      info.put("attributes", attributes);
    }
    if (f.facetSlot() != null) {
      info.put("indexed", true);
    }
    if (f.codelistId() != null) {
      info.put("codelist", codelistRef(codelistService.findById(f.codelistId())));
    }
    if (f.referencedFlowId() != null) {
      MicrodataFlow target = flowsById.get(f.referencedFlowId());
      info.put(
          "references",
          Map.of(
              "flow", ref(target),
              "joinOn",
                  Map.of(f.fieldCode(), "ENTITY_ID", "REPORTING_PERIOD", "REPORTING_PERIOD")));
    }
    return info;
  }

  private static void putIfPresent(Map<String, Object> map, String key, String value) {
    if (value != null && !value.isBlank()) {
      map.put(key, value);
    }
  }

  private static Map<String, Object> flowInfo(MicrodataFlow flow) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("agencyId", flow.agencyId());
    info.put("id", flow.artefactId());
    info.put("version", flow.version());
    info.put("flowRef", ref(flow));
    info.put("name", flow.name());
    putIfPresent(info, "description", flow.description());
    return info;
  }

  private Map<String, String> links(MicrodataFlow flow, HttpServletRequest request) {
    String base = base(request) + "/rest/microdata/" + ref(flow);
    return Map.of("structure", base + "/structure", "data", base);
  }

  private static String ref(MicrodataFlow flow) {
    return flow.agencyId() + "," + flow.artefactId() + "," + flow.version();
  }

  private static String codelistRef(Codelist cl) {
    return cl.agencyId() + ":" + cl.artefactId() + "(" + cl.version() + ")";
  }

  private static String base(HttpServletRequest request) {
    return request.getRequestURL().toString().replaceFirst("/rest/microdata.*$", "");
  }

  static Format resolveFormat(String format, String accept) {
    if (format != null && !format.isBlank()) {
      return switch (format.toLowerCase()) {
        case "csv", "sdmx-csv" -> Format.CSV;
        case "json" -> Format.JSON;
        case "parquet" -> Format.PARQUET;
        default ->
            throw new IllegalArgumentException(
                "Unsupported format '" + format + "': use csv, json or parquet");
      };
    }
    if (accept != null) {
      String a = accept.toLowerCase();
      if (a.contains("parquet")) {
        return Format.PARQUET;
      }
      if (a.contains("json")) {
        return Format.JSON;
      }
    }
    return Format.CSV;
  }

  private static MicrodataStage parseStage(String stage) {
    if (stage == null || stage.isBlank()) {
      return MicrodataStage.BETA;
    }
    try {
      return MicrodataStage.valueOf(stage.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid stage '" + stage + "': use BETA or ALPHA");
    }
  }

  private static Integer intParam(String value, String name) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      int n = Integer.parseInt(value.trim());
      if (n < 0) {
        throw new NumberFormatException();
      }
      return n;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be a non-negative integer");
    }
  }

  private static String withParam(String queryString, String name, long value) {
    List<String> parts = new ArrayList<>();
    if (queryString != null) {
      for (String p : queryString.split("&")) {
        if (!p.startsWith(name + "=")) {
          parts.add(p);
        }
      }
    }
    parts.add(name + "=" + value);
    return String.join("&", parts);
  }
}
