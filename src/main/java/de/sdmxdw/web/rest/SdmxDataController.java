package de.sdmxdw.web.rest;

import de.sdmxdw.data.observation.Observation;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.data.observation.ObservationStage;
import de.sdmxdw.data.series.Series;
import de.sdmxdw.structure.codelist.Code;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataType;
import de.sdmxdw.structure.dsd.Dimension;
import de.sdmxdw.structure.dsd.Measure;
import de.sdmxdw.web.AsOf;
import de.sdmxdw.web.rest.SdmxDataWriter.DataMessage;
import de.sdmxdw.web.rest.SdmxDataWriter.Format;
import de.sdmxdw.web.rest.SdmxDataWriter.ObsData;
import de.sdmxdw.web.rest.SdmxDataWriter.SeriesData;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * SDMX REST data queries for time series.
 *
 * <ul>
 *   <li>SDMX 2.1 style: {@code /rest/data/{flowRef}/{key}/{provider}} with flowRef "ID",
 *       "AGENCY,ID" or "AGENCY,ID,VERSION"; filters startPeriod/endPeriod, key with "+" for OR and
 *       empty segments as wildcard.
 *   <li>SDMX 3.0 style: {@code /rest/data/dataflow/{agency}/{id}/{version}/{key}}, additionally
 *       {@code c[DIM]=A,B} and {@code c[TIME_PERIOD]=ge:2020-Q1+le:2024-Q4}.
 * </ul>
 *
 * Common parameters: asOf (vintage), detail (full, dataonly, serieskeysonly, nodata),
 * firstNObservations, lastNObservations, stage (BETA default; ALPHA for Datenmanager only). Output
 * format by Accept header (SDMX media types) or {@code format=xml|genericxml|csv|json}; default
 * GenericData 2.1 on the 2.1 path and StructureSpecificData 3.0 on the 3.0 path.
 */
@RestController
@RequestMapping("/rest/data")
public class SdmxDataController {

  private final SdmxRestSupport support;
  private final ObservationService observationService;
  private final ObjectMapper objectMapper;

  public SdmxDataController(
      SdmxRestSupport support, ObservationService observationService, ObjectMapper objectMapper) {
    this.support = support;
    this.observationService = observationService;
    this.objectMapper = objectMapper;
  }

  @GetMapping({"/{flowRef}", "/{flowRef}/{key}", "/{flowRef}/{key}/{provider}"})
  public void data21(
      @PathVariable String flowRef,
      @PathVariable(required = false) String key,
      @RequestParam Map<String, String> params,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
      HttpServletResponse response)
      throws IOException {
    String[] ref = SdmxRestSupport.parseFlowRef(flowRef);
    respond(ref[0], ref[1], ref[2], key, params, accept, false, response);
  }

  @GetMapping({
    "/dataflow/{agency}/{id}",
    "/dataflow/{agency}/{id}/{version}",
    "/dataflow/{agency}/{id}/{version}/{key}"
  })
  public void data30(
      @PathVariable String agency,
      @PathVariable String id,
      @PathVariable(required = false) String version,
      @PathVariable(required = false) String key,
      @RequestParam Map<String, String> params,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept,
      HttpServletResponse response)
      throws IOException {
    respond(agency, id, version, key, params, accept, true, response);
  }

  private void respond(
      String agency,
      String id,
      String version,
      String key,
      Map<String, String> params,
      String accept,
      boolean v3Path,
      HttpServletResponse response)
      throws IOException {
    Format format = resolveFormat(params.get("format"), params.get("sdmxVersion"), accept, v3Path);
    DataMessage message = query(agency, id, version, key, params);
    if (message.series().isEmpty()) {
      throw new NoSuchElementException("No results found");
    }
    response.setStatus(HttpServletResponse.SC_OK);
    response.setCharacterEncoding("UTF-8");
    response.setContentType(format.mediaType);
    if (format.fileExtension.equals("csv") && "true".equals(params.get("download"))) {
      response.setHeader(
          HttpHeaders.CONTENT_DISPOSITION,
          "attachment; filename=\"" + message.dataflowId() + ".csv\"");
    }
    SdmxDataWriter.write(message, format, Instant.now(), objectMapper, response.getWriter());
  }

  DataMessage query(
      String agency, String id, String version, String key, Map<String, String> params) {
    StructureCatalog catalog = support.loadCatalog();
    Dataflow dataflow =
        catalog.dataflows.get(support.resolveDataflow(catalog, agency, id, version).id());
    DataStructureDefinition dsd = catalog.dsds.get(dataflow.dsdId());

    List<Dimension> keyDimensions =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() != DataType.DATE)
            .sorted(Comparator.comparingInt(Dimension::position))
            .toList();
    List<String> dimIds =
        keyDimensions.stream().map(d -> catalog.concept(d.conceptId()).code()).toList();
    String timeId =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() == DataType.DATE)
            .map(d -> catalog.concept(d.conceptId()).code())
            .findFirst()
            .orElse("TIME_PERIOD");
    List<Measure> measures =
        dsd.measures().stream().sorted(Comparator.comparing(Measure::id)).toList();
    List<String> measureIds =
        measures.stream().map(m -> catalog.concept(m.conceptId()).code()).toList();

    String dimensionAtObservation = params.get("dimensionAtObservation");
    if (dimensionAtObservation != null && !dimensionAtObservation.equals(timeId)) {
      throw new UnsupportedOperationException(
          "dimensionAtObservation=" + dimensionAtObservation + " is not supported, only " + timeId);
    }

    // key segment and SDMX 3.0 component filters c[DIM]=... -> accepted codes per position
    List<Set<String>> keyFilter = parseKey(key, dimIds.size());
    String[] period = {params.get("startPeriod"), params.get("endPeriod")};
    params.forEach(
        (name, value) -> {
          if (!name.startsWith("c[") || !name.endsWith("]")) {
            return;
          }
          String component = name.substring(2, name.length() - 1);
          if (component.equals(timeId)) {
            parseTimeFilter(value, period);
            return;
          }
          int pos = dimIds.indexOf(component);
          if (pos < 0) {
            throw new IllegalArgumentException("Unknown dimension in filter: " + component);
          }
          Set<String> codes =
              Arrays.stream(value.split("[,+ ]"))
                  .map(v -> v.startsWith("eq:") ? v.substring(3) : v)
                  .filter(v -> !v.isEmpty() && !v.equals("*"))
                  .collect(Collectors.toSet());
          keyFilter.set(
              pos, keyFilter.get(pos) == null ? codes : intersect(keyFilter.get(pos), codes));
        });

    Map<Long, Series> seriesById = new HashMap<>();
    for (Series s : observationService.findSeriesByDataflow(dataflow.id())) {
      if (matchesKey(keyFilter, s.seriesKey().split("\\.", -1))) {
        seriesById.put(s.id(), s);
      }
    }

    ObservationStage stage = parseStage(params.get("stage"));
    Instant asOf = AsOf.parse(params.get("asOf"));
    String detail = params.getOrDefault("detail", "full").toLowerCase();
    boolean keysOnly = detail.equals("serieskeysonly") || detail.equals("nodata");
    List<Observation> raw =
        seriesById.isEmpty()
            ? List.of()
            : asOf == null
                ? observationService.findCurrentForSeriesIds(
                    dataflow.id(), stage, seriesById.keySet())
                : observationService.findAsOfForSeriesIds(
                    dataflow.id(), stage, seriesById.keySet(), asOf);
    Map<Long, List<Observation>> bySeries =
        raw.stream()
            .filter(o -> withinPeriod(o.timePeriod(), period[0], period[1]))
            .collect(Collectors.groupingBy(Observation::seriesId));
    Integer firstN = intParam(params, "firstNObservations");
    Integer lastN = intParam(params, "lastNObservations");

    List<SeriesData> series = new ArrayList<>();
    bySeries.entrySet().stream()
        .sorted(Comparator.comparing(e -> seriesById.get(e.getKey()).seriesKey()))
        .forEach(
            e -> {
              List<Observation> obs =
                  e.getValue().stream()
                      .sorted(Comparator.comparing(Observation::timePeriod))
                      .toList();
              if (firstN != null && obs.size() > firstN) {
                obs = obs.subList(0, firstN);
              }
              if (lastN != null && obs.size() > lastN) {
                obs = obs.subList(obs.size() - lastN, obs.size());
              }
              List<ObsData> obsData =
                  keysOnly
                      ? List.of()
                      : obs.stream()
                          .map(
                              o -> {
                                Map<Long, String> values =
                                    observationService.parseMeasureValues(o.measureValuesJson());
                                return new ObsData(
                                    o.timePeriod(),
                                    measures.stream().map(m -> values.get(m.id())).toList());
                              })
                          .toList();
              List<String> keyValues =
                  new ArrayList<>(
                      Arrays.asList(seriesById.get(e.getKey()).seriesKey().split("\\.", -1)));
              while (keyValues.size() < dimIds.size()) {
                keyValues.add("");
              }
              series.add(new SeriesData(keyValues.subList(0, dimIds.size()), obsData));
            });

    Map<String, Map<String, String>> codeNames = new HashMap<>();
    Map<String, String> componentNames = new LinkedHashMap<>();
    for (int i = 0; i < keyDimensions.size(); i++) {
      Dimension d = keyDimensions.get(i);
      componentNames.put(dimIds.get(i), catalog.concept(d.conceptId()).name());
      Codelist cl = d.codelistId() == null ? null : catalog.codelists.get(d.codelistId());
      if (cl != null) {
        codeNames.put(
            dimIds.get(i),
            cl.codes().stream().collect(Collectors.toMap(Code::code, Code::name, (a, b) -> a)));
      }
    }
    measures.forEach(
        m ->
            componentNames.put(
                catalog.concept(m.conceptId()).code(), catalog.concept(m.conceptId()).name()));

    return new DataMessage(
        dataflow.agencyId(),
        dataflow.artefactId(),
        dataflow.version(),
        dimIds,
        timeId,
        measureIds,
        series,
        codeNames,
        componentNames);
  }

  static Format resolveFormat(String format, String sdmxVersion, String accept, boolean v3Path) {
    boolean v21 = sdmxVersion != null ? sdmxVersion.startsWith("2") : !v3Path;
    if (format != null && !format.isBlank()) {
      return switch (format.toLowerCase()) {
        case "csv", "sdmx-csv" -> sdmxVersion != null && v21 ? Format.CSV_10 : Format.CSV_20;
        case "json", "sdmx-json" -> Format.JSON_10;
        case "xml", "structurespecific", "structurespecificdata" ->
            v21 ? Format.STRUCTURE_SPECIFIC_21 : Format.STRUCTURE_SPECIFIC_30;
        case "genericxml", "generic", "genericdata" -> Format.GENERIC_21;
        default ->
            throw new IllegalArgumentException(
                "Unsupported format '" + format + "': use xml, genericxml, csv or json");
      };
    }
    if (accept != null) {
      String a = accept.toLowerCase();
      if (a.contains("structurespecificdata+xml")) {
        return Format.STRUCTURE_SPECIFIC_21;
      }
      if (a.contains("genericdata+xml") || a.contains("generic+xml")) {
        return Format.GENERIC_21;
      }
      if (a.contains("vnd.sdmx.data+xml")) {
        return a.contains("version=3.1")
            ? Format.STRUCTURE_SPECIFIC_31
            : Format.STRUCTURE_SPECIFIC_30;
      }
      if (a.contains("data+csv")) {
        return a.contains("version=1") ? Format.CSV_10 : Format.CSV_20;
      }
      if (a.contains("text/csv")) {
        return Format.CSV_20;
      }
      if (a.contains("json")) {
        return Format.JSON_10;
      }
    }
    return v21 ? Format.GENERIC_21 : Format.STRUCTURE_SPECIFIC_30;
  }

  private static ObservationStage parseStage(String stage) {
    if (stage == null || stage.isBlank()) {
      return ObservationStage.BETA;
    }
    try {
      return ObservationStage.valueOf(stage.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid stage '" + stage + "': use BETA or ALPHA");
    }
  }

  private static Integer intParam(Map<String, String> params, String name) {
    String value = params.get(name);
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

  /** One entry per key dimension: null = wildcard, otherwise the accepted codes. */
  static List<Set<String>> parseKey(String key, int dims) {
    List<Set<String>> filter = new ArrayList<>();
    for (int i = 0; i < dims; i++) {
      filter.add(null);
    }
    if (key == null || key.isBlank() || "all".equalsIgnoreCase(key) || "*".equals(key)) {
      return filter;
    }
    String[] segments = key.split("\\.", -1);
    if (segments.length != dims) {
      throw new IllegalArgumentException(
          "Key '" + key + "' has " + segments.length + " dimensions, the dataflow has " + dims);
    }
    for (int i = 0; i < dims; i++) {
      String segment = segments[i];
      if (!segment.isEmpty() && !segment.equals("*")) {
        filter.set(i, new HashSet<>(Arrays.asList(segment.split("[+,]"))));
      }
    }
    return filter;
  }

  private static boolean matchesKey(List<Set<String>> filter, String[] values) {
    for (int i = 0; i < filter.size(); i++) {
      Set<String> accepted = filter.get(i);
      if (accepted != null && (i >= values.length || !accepted.contains(values[i]))) {
        return false;
      }
    }
    return true;
  }

  private static Set<String> intersect(Set<String> a, Set<String> b) {
    Set<String> result = new HashSet<>(a);
    result.retainAll(b);
    return result;
  }

  /** SDMX 3.0 time filter, e.g. "ge:2020-Q1+le:2024-Q4" ("+" may arrive decoded as a space). */
  private static void parseTimeFilter(String value, String[] period) {
    for (String part : value.split("[+ ,]")) {
      if (part.startsWith("ge:") || part.startsWith("gt:")) {
        period[0] = part.substring(3);
      } else if (part.startsWith("le:") || part.startsWith("lt:")) {
        period[1] = part.substring(3);
      } else if (!part.isEmpty()) {
        String v = part.startsWith("eq:") ? part.substring(3) : part;
        period[0] = v;
        period[1] = v;
      }
    }
  }

  private static boolean withinPeriod(String timePeriod, String start, String end) {
    if (start != null && !start.isBlank() && timePeriod.compareTo(start) < 0) {
      return false;
    }
    return end == null || end.isBlank() || timePeriod.compareTo(end) <= 0;
  }
}
