package de.sdmxdw.process;

import de.sdmxdw.data.BulkLoadError;
import de.sdmxdw.data.BulkLoadResult;
import de.sdmxdw.data.observation.ObservationBulkLoader;
import de.sdmxdw.microdata.MicrodataBulkLoader;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The file handoff: loads everything a step wrote to its output folder. The {@code STRUCTURE_ID}
 * column (or {@code DATAFLOW} in SDMX-CSV 1.0) decides the target: {@code AG:ID(VERSION)} for a
 * dataflow or microdata flow, {@code AG:ID(VERSION)/GROUP} for repeating group rows (with
 * ENTITY_ID, REPORTING_PERIOD, optional ITEM_NO). All targets of a step are loaded in one
 * transaction: if any file is invalid nothing is written; a dry run validates and counts, then
 * rolls back.
 */
@Component
public class ProcessLoader {

  private static final Pattern REF = Pattern.compile("([^:/]+):([^:(/]+)\\(([^)]+)\\)(?:/(.+))?");
  private static final Set<String> MICRODATA_KEYS =
      Set.of(
          "STRUCTURE",
          "STRUCTURE_ID",
          "ACTION",
          "DATAFLOW",
          "ENTITY_ID",
          "REPORTING_PERIOD",
          "ITEM_NO");

  private final DataflowService dataflowService;
  private final MicrodataFlowService microdataFlowService;
  private final ObservationBulkLoader observationLoader;
  private final MicrodataBulkLoader microdataLoader;
  private final TransactionTemplate transactions;

  public ProcessLoader(
      DataflowService dataflowService,
      MicrodataFlowService microdataFlowService,
      ObservationBulkLoader observationLoader,
      MicrodataBulkLoader microdataLoader,
      TransactionTemplate transactions) {
    this.dataflowService = dataflowService;
    this.microdataFlowService = microdataFlowService;
    this.observationLoader = observationLoader;
    this.microdataLoader = microdataLoader;
    this.transactions = transactions;
  }

  /** Result of loading one step's output folder; serialised into the step run. */
  public record StepLoadReport(
      boolean dryRun, List<TargetReport> targets, List<String> errors, List<String> files) {

    public boolean failed() {
      return !errors.isEmpty();
    }
  }

  /**
   * @param kind "dataflow" or "microdata"; id = internal id for links
   */
  public record TargetReport(
      String kind, Long id, String flowRef, int rows, int written, int unchanged) {}

  static List<Path> filesIn(Path outDir) {
    if (!Files.isDirectory(outDir)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(outDir)) {
      return walk.filter(Files::isRegularFile)
          .filter(HandoffFileReader::supported)
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public StepLoadReport load(Path outDir, boolean dryRun, String recordedBy) {
    List<Path> files = filesIn(outDir);
    List<String> fileNames = files.stream().map(f -> outDir.relativize(f).toString()).toList();
    if (files.isEmpty()) {
      return new StepLoadReport(dryRun, List.of(), List.of(), List.of());
    }
    List<BulkLoadError> errors = new ArrayList<>();
    // target -> rows; group rows keyed by "flowRef" and group code
    Map<String, List<ObservationBulkLoader.Row>> observationRows = new LinkedHashMap<>();
    Map<String, List<MainRow>> microdataRows = new LinkedHashMap<>();
    Map<String, Map<String, List<GroupRow>>> groupRows = new LinkedHashMap<>();
    Map<String, Dataflow> dataflows = new HashMap<>();
    Map<String, MicrodataFlow> microdataFlows = new HashMap<>();
    dataflowService
        .findAll()
        .forEach(d -> dataflows.put(ref(d.agencyId(), d.artefactId(), d.version()), d));
    microdataFlowService
        .findAll()
        .forEach(f -> microdataFlows.put(ref(f.agencyId(), f.artefactId(), f.version()), f));

    for (Path file : files) {
      String name = outDir.relativize(file).toString();
      HandoffFileReader.Table table;
      try {
        table = HandoffFileReader.read(file);
      } catch (SQLException e) {
        errors.add(new BulkLoadError(name, 0, "Cannot read file: " + e.getMessage()));
        continue;
      }
      int structureColumn = table.columns().indexOf("STRUCTURE_ID");
      if (structureColumn < 0) {
        structureColumn = table.columns().indexOf("DATAFLOW");
      }
      if (structureColumn < 0) {
        errors.add(new BulkLoadError(name, 0, "Missing column STRUCTURE_ID (SDMX-CSV)"));
        continue;
      }
      int actionColumn = table.columns().indexOf("ACTION");
      for (int r = 0; r < table.rows().size(); r++) {
        List<String> row = table.rows().get(r);
        int rowNumber = r + 1;
        String action = actionColumn < 0 ? null : row.get(actionColumn);
        if (action != null && !action.isBlank() && !action.equals("I") && !action.equals("R")) {
          errors.add(
              new BulkLoadError(
                  name, rowNumber, "ACTION '" + action + "' is not supported (only I/R)"));
          continue;
        }
        String structure = row.get(structureColumn);
        Matcher m = structure == null ? null : REF.matcher(structure.strip());
        if (m == null || !m.matches()) {
          errors.add(
              new BulkLoadError(name, rowNumber, "Invalid STRUCTURE_ID '" + structure + "'"));
          continue;
        }
        String target = ref(m.group(1), m.group(2), m.group(3));
        String group = m.group(4);
        Map<String, String> values = new LinkedHashMap<>();
        // empty values stay in the map: unknown columns are reported even when always empty
        for (int c = 0; c < table.columns().size(); c++) {
          values.put(table.columns().get(c), row.get(c));
        }
        if (dataflows.containsKey(target) && group == null) {
          observationRows
              .computeIfAbsent(target, k -> new ArrayList<>())
              .add(new ObservationBulkLoader.Row(name, rowNumber, values));
        } else if (microdataFlows.containsKey(target)) {
          if (group == null) {
            microdataRows
                .computeIfAbsent(target, k -> new ArrayList<>())
                .add(new MainRow(name, rowNumber, values));
          } else {
            groupRows
                .computeIfAbsent(target, k -> new LinkedHashMap<>())
                .computeIfAbsent(group, k -> new ArrayList<>())
                .add(new GroupRow(name, rowNumber, values));
          }
        } else if (errors.stream().noneMatch(e -> e.message().contains("'" + target + "'"))) {
          errors.add(new BulkLoadError(name, rowNumber, "Unknown flow '" + target + "'"));
        }
      }
    }

    // assemble microdata records (main row + group rows)
    Map<String, List<MicrodataBulkLoader.Record>> records = new LinkedHashMap<>();
    Map<Long, Set<String>> delivered = new HashMap<>();
    for (Map.Entry<String, List<MainRow>> e : microdataRows.entrySet()) {
      List<MicrodataBulkLoader.Record> list = new ArrayList<>();
      Map<String, Map<String, List<Map<String, String>>>> groupsByRecord =
          groupsByRecord(groupRows.getOrDefault(e.getKey(), Map.of()));
      for (MainRow row : e.getValue()) {
        String entity = row.values().get("ENTITY_ID");
        String period = row.values().get("REPORTING_PERIOD");
        Map<String, String> scalars = new LinkedHashMap<>(row.values());
        MICRODATA_KEYS.forEach(scalars::remove);
        Map<String, List<Map<String, String>>> groups =
            groupsByRecord.getOrDefault(entity + "|" + period, Map.of());
        list.add(
            new MicrodataBulkLoader.Record(
                row.file(), row.rowNumber(), entity, period, scalars, groups));
        delivered
            .computeIfAbsent(microdataFlows.get(e.getKey()).id(), k -> new HashSet<>())
            .add(entity + "|" + period);
      }
      records.put(e.getKey(), list);
    }
    // group rows need their main record in the same delivery
    groupRows.forEach(
        (target, byGroup) -> {
          Set<String> mains = new HashSet<>();
          microdataRows
              .getOrDefault(target, List.of())
              .forEach(
                  r ->
                      mains.add(
                          r.values().get("ENTITY_ID") + "|" + r.values().get("REPORTING_PERIOD")));
          byGroup.forEach(
              (group, rows) -> {
                for (GroupRow r : rows) {
                  String key =
                      r.values().get("ENTITY_ID") + "|" + r.values().get("REPORTING_PERIOD");
                  if (!mains.contains(key)) {
                    errors.add(
                        new BulkLoadError(
                            r.file(),
                            r.rowNumber(),
                            "Group row for "
                                + key.replace("|", " @ ")
                                + " without its main record in the delivery"));
                    return;
                  }
                }
              });
        });

    List<TargetReport> targets = new ArrayList<>();
    if (errors.isEmpty()) {
      transactions.executeWithoutResult(
          status -> {
            for (Map.Entry<String, List<ObservationBulkLoader.Row>> e :
                observationRows.entrySet()) {
              Dataflow df = dataflows.get(e.getKey());
              BulkLoadResult result = observationLoader.load(df.id(), e.getValue(), recordedBy);
              collect(result, "dataflow", df.id(), targets, errors);
            }
            for (Map.Entry<String, List<MicrodataBulkLoader.Record>> e : records.entrySet()) {
              MicrodataFlow flow = microdataFlows.get(e.getKey());
              BulkLoadResult result =
                  microdataLoader.load(flow.id(), e.getValue(), delivered, recordedBy);
              collect(result, "microdata", flow.id(), targets, errors);
            }
            if (dryRun || !errors.isEmpty()) {
              status.setRollbackOnly();
            }
          });
    }
    return new StepLoadReport(
        dryRun,
        errors.isEmpty() ? targets : List.of(),
        errors.stream().limit(100).map(BulkLoadError::toString).toList(),
        fileNames);
  }

  private static void collect(
      BulkLoadResult result,
      String kind,
      Long id,
      List<TargetReport> targets,
      List<BulkLoadError> errors) {
    errors.addAll(result.errors());
    targets.add(
        new TargetReport(
            kind, id, result.flowRef(), result.rows(), result.written(), result.unchanged()));
  }

  /** entity|period -> group -> items, ordered by ITEM_NO when given. */
  private static Map<String, Map<String, List<Map<String, String>>>> groupsByRecord(
      Map<String, List<GroupRow>> byGroup) {
    Map<String, Map<String, List<Map<String, String>>>> result = new HashMap<>();
    byGroup.forEach(
        (group, rows) -> {
          Map<String, TreeMap<Double, List<Map<String, String>>>> ordered = new HashMap<>();
          int fallback = 0;
          for (GroupRow r : rows) {
            String key = r.values().get("ENTITY_ID") + "|" + r.values().get("REPORTING_PERIOD");
            double order;
            try {
              order = Double.parseDouble(r.values().getOrDefault("ITEM_NO", ""));
            } catch (NumberFormatException e) {
              order = 1_000_000 + fallback;
            }
            fallback++;
            Map<String, String> item = new LinkedHashMap<>(r.values());
            MICRODATA_KEYS.forEach(item::remove);
            ordered
                .computeIfAbsent(key, k -> new TreeMap<>())
                .computeIfAbsent(order, k -> new ArrayList<>())
                .add(item);
          }
          ordered.forEach(
              (key, tree) -> {
                List<Map<String, String>> items = new ArrayList<>();
                tree.values().forEach(items::addAll);
                result.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(group, items);
              });
        });
    return result;
  }

  static String ref(String agency, String id, String version) {
    return agency + ":" + id + "(" + version + ")";
  }

  private record MainRow(String file, int rowNumber, Map<String, String> values) {}

  private record GroupRow(String file, int rowNumber, Map<String, String> values) {}
}
