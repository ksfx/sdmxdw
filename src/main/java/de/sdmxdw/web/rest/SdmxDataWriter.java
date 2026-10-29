package de.sdmxdw.web.rest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes time series data in the SDMX exchange formats. All formats share one model: the key
 * dimensions in DSD order, the time dimension at observation level and the measures. Under SDMX 2.1
 * conventions (GenericData 2.1, StructureSpecificData 2.1, SDMX-CSV 1.0, SDMX-JSON 1.0) only one
 * primary measure exists, so the first measure is written as OBS_VALUE and further measures as
 * observation attributes - matching the 2.1 structure message of {@link SdmxStructureWriter}.
 */
public final class SdmxDataWriter {

  public enum Format {
    GENERIC_21("application/vnd.sdmx.genericdata+xml;version=2.1", "xml"),
    STRUCTURE_SPECIFIC_21("application/vnd.sdmx.structurespecificdata+xml;version=2.1", "xml"),
    STRUCTURE_SPECIFIC_30("application/vnd.sdmx.data+xml;version=3.0.0", "xml"),
    STRUCTURE_SPECIFIC_31("application/vnd.sdmx.data+xml;version=3.1.0", "xml"),
    CSV_10("application/vnd.sdmx.data+csv;version=1.0.0", "csv"),
    CSV_20("application/vnd.sdmx.data+csv;version=2.0.0", "csv"),
    JSON_10("application/vnd.sdmx.data+json;version=1.0.0", "json");

    public final String mediaType;
    public final String fileExtension;

    Format(String mediaType, String fileExtension) {
      this.mediaType = mediaType;
      this.fileExtension = fileExtension;
    }

    boolean singlePrimaryMeasure() {
      return this == GENERIC_21
          || this == STRUCTURE_SPECIFIC_21
          || this == CSV_10
          || this == JSON_10;
    }
  }

  /**
   * @param codeNames dimension id -> code -> name (labels for SDMX-JSON; may be incomplete)
   * @param componentNames dimension/measure id -> concept name
   */
  public record DataMessage(
      String agencyId,
      String dataflowId,
      String dataflowVersion,
      List<String> dimensionIds,
      String timeDimensionId,
      List<String> measureIds,
      List<SeriesData> series,
      Map<String, Map<String, String>> codeNames,
      Map<String, String> componentNames) {

    String flowRef() {
      return agencyId + ":" + dataflowId + "(" + dataflowVersion + ")";
    }

    String dataflowUrn() {
      return "urn:sdmx:org.sdmx.infomodel.datastructure.Dataflow=" + flowRef();
    }

    String structureId() {
      return (agencyId + "_" + dataflowId + "_" + dataflowVersion).replaceAll("[^A-Za-z0-9_]", "_");
    }
  }

  /**
   * @param keyValues aligned with {@link DataMessage#dimensionIds()}
   */
  public record SeriesData(List<String> keyValues, List<ObsData> observations) {}

  /**
   * @param values aligned with {@link DataMessage#measureIds()}
   */
  public record ObsData(String timePeriod, List<String> values) {}

  private SdmxDataWriter() {}

  /** For the XML and CSV formats, which need no JSON mapper. */
  public static void write(DataMessage msg, Format format, Instant prepared, Writer out) {
    if (format == Format.JSON_10) {
      throw new IllegalArgumentException("SDMX-JSON needs an ObjectMapper");
    }
    write(msg, format, prepared, null, out);
  }

  public static void write(
      DataMessage msg, Format format, Instant prepared, ObjectMapper json, Writer out) {
    try {
      switch (format) {
        case GENERIC_21 -> generic21(msg, prepared, out);
        case STRUCTURE_SPECIFIC_21 -> structureSpecific(msg, "v2_1", prepared, out);
        case STRUCTURE_SPECIFIC_30 -> structureSpecific(msg, "v3_0", prepared, out);
        case STRUCTURE_SPECIFIC_31 -> structureSpecific(msg, "v3_1", prepared, out);
        case CSV_10 -> csv10(msg, out);
        case CSV_20 -> csv20(msg, out);
        case JSON_10 -> json10(msg, prepared, json, out);
      }
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Measure ids as written by the format: OBS_VALUE + attributes under 2.1 conventions. */
  static List<String> outputMeasureIds(DataMessage msg, Format format) {
    if (!format.singlePrimaryMeasure() || msg.measureIds().isEmpty()) {
      return msg.measureIds();
    }
    List<String> ids = new ArrayList<>();
    ids.add(SdmxStructureWriter.PRIMARY_MEASURE_21);
    ids.addAll(msg.measureIds().subList(1, msg.measureIds().size()));
    return ids;
  }

  // --- SDMX-ML -----------------------------------------------------------------------------------

  private static void header(
      DataMessage msg, String schemas, Instant prepared, boolean structureSpecific, Writer out)
      throws IOException {
    boolean v21 = schemas.endsWith("v2_1");
    String namespace = msg.dataflowUrn() + ":ObsLevelDim:" + msg.timeDimensionId();
    out.write(
        "  <message:Header>\n    <message:ID>SDMXDW_"
            + prepared.toEpochMilli()
            + "</message:ID>\n");
    out.write("    <message:Test>false</message:Test>\n");
    out.write(
        "    <message:Prepared>"
            + prepared.truncatedTo(ChronoUnit.SECONDS)
            + "</message:Prepared>\n");
    out.write("    <message:Sender id=\"SDMXDW\"/>\n");
    out.write("    <message:Structure structureID=\"" + msg.structureId() + "\"");
    if (structureSpecific) {
      out.write(" namespace=\"" + esc(namespace) + "\"");
    }
    out.write(" dimensionAtObservation=\"" + esc(msg.timeDimensionId()) + "\">\n");
    if (v21) {
      // 2.1 references maintainables via a Ref element, 3.x via URN text
      out.write(
          "      <common:StructureUsage><Ref agencyID=\""
              + esc(msg.agencyId())
              + "\" id=\""
              + esc(msg.dataflowId())
              + "\" version=\""
              + esc(msg.dataflowVersion())
              + "\"/></common:StructureUsage>\n");
    } else {
      out.write(
          "      <common:StructureUsage>" + esc(msg.dataflowUrn()) + "</common:StructureUsage>\n");
    }
    out.write("    </message:Structure>\n  </message:Header>\n");
  }

  private static void structureSpecific(
      DataMessage msg, String schemaPath, Instant prepared, Writer out) throws IOException {
    String schemas = "http://www.sdmx.org/resources/sdmxml/schemas/" + schemaPath;
    String namespace = msg.dataflowUrn() + ":ObsLevelDim:" + msg.timeDimensionId();
    Format format =
        "v2_1".equals(schemaPath) ? Format.STRUCTURE_SPECIFIC_21 : Format.STRUCTURE_SPECIFIC_30;
    List<String> measureIds = outputMeasureIds(msg, format);
    out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    out.write(
        "<message:StructureSpecificData xmlns:message=\""
            + schemas
            + "/message\" xmlns:common=\""
            + schemas
            + "/common\" xmlns:ss=\""
            + schemas
            + "/data/structurespecific\" xmlns:ns1=\""
            + esc(namespace)
            + "\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
    header(msg, schemas, prepared, true, out);
    out.write(
        "  <message:DataSet ss:structureRef=\""
            + msg.structureId()
            + "\" xsi:type=\"ns1:DataSetType\" ss:dataScope=\"DataStructure\""
            + " action=\"Information\">\n");
    for (SeriesData s : msg.series()) {
      out.write("    <Series");
      for (int i = 0; i < msg.dimensionIds().size(); i++) {
        attribute(out, msg.dimensionIds().get(i), s.keyValues().get(i));
      }
      if (s.observations().isEmpty()) {
        out.write("/>\n");
        continue;
      }
      out.write(">\n");
      for (ObsData o : s.observations()) {
        out.write("      <Obs");
        attribute(out, msg.timeDimensionId(), o.timePeriod());
        for (int i = 0; i < measureIds.size(); i++) {
          attribute(out, measureIds.get(i), o.values().get(i));
        }
        out.write("/>\n");
      }
      out.write("    </Series>\n");
    }
    out.write("  </message:DataSet>\n</message:StructureSpecificData>\n");
  }

  private static void generic21(DataMessage msg, Instant prepared, Writer out) throws IOException {
    String schemas = "http://www.sdmx.org/resources/sdmxml/schemas/v2_1";
    List<String> measureIds = outputMeasureIds(msg, Format.GENERIC_21);
    out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    out.write(
        "<message:GenericData xmlns:message=\""
            + schemas
            + "/message\" xmlns:common=\""
            + schemas
            + "/common\" xmlns:generic=\""
            + schemas
            + "/data/generic\">\n");
    header(msg, schemas, prepared, false, out);
    out.write(
        "  <message:DataSet structureRef=\"" + msg.structureId() + "\" action=\"Information\">\n");
    for (SeriesData s : msg.series()) {
      out.write("    <generic:Series>\n      <generic:SeriesKey>\n");
      for (int i = 0; i < msg.dimensionIds().size(); i++) {
        out.write(
            "        <generic:Value id=\""
                + esc(msg.dimensionIds().get(i))
                + "\" value=\""
                + esc(s.keyValues().get(i))
                + "\"/>\n");
      }
      out.write("      </generic:SeriesKey>\n");
      for (ObsData o : s.observations()) {
        out.write("      <generic:Obs>\n");
        out.write("        <generic:ObsDimension value=\"" + esc(o.timePeriod()) + "\"/>\n");
        String primary = o.values().isEmpty() ? null : o.values().getFirst();
        if (primary != null && !primary.isEmpty()) {
          out.write("        <generic:ObsValue value=\"" + esc(primary) + "\"/>\n");
        }
        StringBuilder attrs = new StringBuilder();
        for (int i = 1; i < measureIds.size(); i++) {
          String value = o.values().get(i);
          if (value != null && !value.isEmpty()) {
            attrs
                .append("          <generic:Value id=\"")
                .append(esc(measureIds.get(i)))
                .append("\" value=\"")
                .append(esc(value))
                .append("\"/>\n");
          }
        }
        if (!attrs.isEmpty()) {
          out.write("        <generic:Attributes>\n" + attrs + "        </generic:Attributes>\n");
        }
        out.write("      </generic:Obs>\n");
      }
      out.write("    </generic:Series>\n");
    }
    out.write("  </message:DataSet>\n</message:GenericData>\n");
  }

  // --- SDMX-CSV ----------------------------------------------------------------------------------

  private static void csv10(DataMessage msg, Writer out) throws IOException {
    List<String> measureIds = outputMeasureIds(msg, Format.CSV_10);
    StringBuilder head = new StringBuilder("DATAFLOW");
    msg.dimensionIds().forEach(d -> head.append(',').append(d));
    head.append(',').append(msg.timeDimensionId());
    measureIds.forEach(m -> head.append(',').append(m));
    out.write(head.append('\n').toString());
    String flowRef =
        csv(msg.agencyId() + ":" + msg.dataflowId() + "(" + msg.dataflowVersion() + ")");
    csvRows(msg, flowRef, out);
  }

  private static void csv20(DataMessage msg, Writer out) throws IOException {
    StringBuilder head = new StringBuilder("STRUCTURE,STRUCTURE_ID,ACTION");
    msg.dimensionIds().forEach(d -> head.append(',').append(d));
    head.append(',').append(msg.timeDimensionId());
    msg.measureIds().forEach(m -> head.append(',').append(m));
    out.write(head.append('\n').toString());
    csvRows(msg, "dataflow," + csv(msg.flowRef()) + ",I", out);
  }

  private static void csvRows(DataMessage msg, String prefix, Writer out) throws IOException {
    StringBuilder row = new StringBuilder();
    for (SeriesData s : msg.series()) {
      for (ObsData o : s.observations()) {
        row.setLength(0);
        row.append(prefix);
        s.keyValues().forEach(v -> row.append(',').append(csv(v)));
        row.append(',').append(csv(o.timePeriod()));
        o.values().forEach(v -> row.append(',').append(csv(v)));
        out.write(row.append('\n').toString());
      }
    }
  }

  static String csv(String value) {
    if (value == null) {
      return "";
    }
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }

  // --- SDMX-JSON 1.0 -----------------------------------------------------------------------------

  /**
   * SDMX-JSON 1.0 data message: dimension values are listed once in "structure" and referenced by
   * index; series keys are colon-separated index lists ("0:2:1"), observations keyed by the index
   * of their time period. Numeric values are written as JSON numbers.
   */
  private static void json10(DataMessage msg, Instant prepared, ObjectMapper json, Writer out)
      throws IOException {
    List<String> measureIds = outputMeasureIds(msg, Format.JSON_10);
    int dims = msg.dimensionIds().size();
    List<Map<String, Integer>> valueIndex = new ArrayList<>();
    for (int i = 0; i < dims; i++) {
      valueIndex.add(new LinkedHashMap<>());
    }
    Map<String, Integer> timeIndex = new LinkedHashMap<>();
    List<Map<String, Integer>> attrIndex = new ArrayList<>();
    for (int i = 1; i < measureIds.size(); i++) {
      attrIndex.add(new LinkedHashMap<>());
    }
    msg.series()
        .forEach(
            s -> {
              for (int i = 0; i < dims; i++) {
                valueIndex.get(i).putIfAbsent(s.keyValues().get(i), valueIndex.get(i).size());
              }
              s.observations().stream()
                  .map(ObsData::timePeriod)
                  .sorted()
                  .forEach(t -> timeIndex.putIfAbsent(t, 0));
            });
    List<String> periods = timeIndex.keySet().stream().sorted().toList();
    for (int i = 0; i < periods.size(); i++) {
      timeIndex.put(periods.get(i), i);
    }

    Map<String, Object> seriesJson = new LinkedHashMap<>();
    for (SeriesData s : msg.series()) {
      StringBuilder key = new StringBuilder();
      for (int i = 0; i < dims; i++) {
        key.append(i == 0 ? "" : ":").append(valueIndex.get(i).get(s.keyValues().get(i)));
      }
      Map<String, Object> observations = new LinkedHashMap<>();
      for (ObsData o : s.observations()) {
        List<Object> entry = new ArrayList<>();
        entry.add(o.values().isEmpty() ? null : number(o.values().getFirst()));
        for (int i = 1; i < measureIds.size(); i++) {
          String v = o.values().get(i);
          if (v == null || v.isEmpty()) {
            entry.add(null);
          } else {
            Map<String, Integer> idx = attrIndex.get(i - 1);
            entry.add(idx.computeIfAbsent(v, x -> idx.size()));
          }
        }
        observations.put(String.valueOf(timeIndex.get(o.timePeriod())), entry);
      }
      Map<String, Object> seriesEntry = new LinkedHashMap<>();
      seriesEntry.put("attributes", List.of());
      seriesEntry.put("observations", observations);
      seriesJson.put(key.toString(), seriesEntry);
    }

    List<Object> seriesDims = new ArrayList<>();
    for (int i = 0; i < dims; i++) {
      String dimId = msg.dimensionIds().get(i);
      Map<String, String> names = msg.codeNames().getOrDefault(dimId, Map.of());
      List<Object> values = new ArrayList<>();
      valueIndex
          .get(i)
          .keySet()
          .forEach(code -> values.add(Map.of("id", code, "name", names.getOrDefault(code, code))));
      Map<String, Object> dim = new LinkedHashMap<>();
      dim.put("id", dimId);
      dim.put("name", msg.componentNames().getOrDefault(dimId, dimId));
      dim.put("keyPosition", i);
      dim.put("values", values);
      seriesDims.add(dim);
    }
    Map<String, Object> timeDim = new LinkedHashMap<>();
    timeDim.put("id", msg.timeDimensionId());
    timeDim.put("name", msg.componentNames().getOrDefault(msg.timeDimensionId(), "Time period"));
    timeDim.put("keyPosition", dims);
    timeDim.put("role", "time");
    timeDim.put("values", periods.stream().map(p -> Map.of("id", p, "name", p)).toList());

    List<Object> obsAttributes = new ArrayList<>();
    for (int i = 1; i < measureIds.size(); i++) {
      String id = measureIds.get(i);
      obsAttributes.add(
          Map.of(
              "id", id,
              "name", msg.componentNames().getOrDefault(id, id),
              "values",
                  attrIndex.get(i - 1).keySet().stream()
                      .map(v -> Map.of("id", v, "name", v))
                      .toList()));
    }

    Map<String, Object> root = new LinkedHashMap<>();
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("id", "SDMXDW_" + prepared.toEpochMilli());
    header.put("test", false);
    header.put("prepared", prepared.truncatedTo(ChronoUnit.SECONDS).toString());
    header.put("sender", Map.of("id", "SDMXDW"));
    root.put("header", header);
    Map<String, Object> dataSet = new LinkedHashMap<>();
    dataSet.put("action", "Information");
    dataSet.put("series", seriesJson);
    root.put("dataSets", List.of(dataSet));
    Map<String, Object> structure = new LinkedHashMap<>();
    structure.put("links", List.of(Map.of("rel", "dataflow", "urn", msg.dataflowUrn())));
    structure.put("name", msg.dataflowId());
    structure.put(
        "dimensions",
        Map.of("dataset", List.of(), "series", seriesDims, "observation", List.of(timeDim)));
    structure.put(
        "attributes",
        Map.of("dataset", List.of(), "series", List.of(), "observation", obsAttributes));
    root.put("structure", structure);
    json.writeValue(out, root);
  }

  private static Object number(String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    try {
      return new java.math.BigDecimal(value);
    } catch (NumberFormatException e) {
      return value;
    }
  }

  private static void attribute(Writer out, String name, String value) throws IOException {
    if (value == null || value.isEmpty()) {
      return;
    }
    out.write(" " + name + "=\"" + esc(value) + "\"");
  }

  static String esc(String value) {
    return SdmxStructureWriter.esc(value);
  }
}
