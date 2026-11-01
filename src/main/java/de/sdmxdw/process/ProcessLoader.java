package de.sdmxdw.process;

import de.sdmxdw.data.BulkLoadError;
import de.sdmxdw.data.BulkLoadResult;
import de.sdmxdw.data.observation.ObservationBulkLoader;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataStagingLoader;
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
 * rolls back. Files are read row by row; microdata rows go straight into staging tables, so the
 * size of a delivery is not limited by memory (#17).
 */
@Component
public class ProcessLoader {

  private static final Pattern REF = Pattern.compile("([^:/]+):([^:(/]+)\\(([^)]+)\\)(?:/(.+))?");

  private final DataflowService dataflowService;
  private final MicrodataFlowService microdataFlowService;
  private final ObservationBulkLoader observationLoader;
  private final MicrodataStagingLoader microdataLoader;
  private final TransactionTemplate transactions;

  public ProcessLoader(
      DataflowService dataflowService,
      MicrodataFlowService microdataFlowService,
      ObservationBulkLoader observationLoader,
      MicrodataStagingLoader microdataLoader,
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
    Map<String, Dataflow> dataflows = new HashMap<>();
    Map<String, MicrodataFlow> microdataFlows = new HashMap<>();
    dataflowService
        .findAll()
        .forEach(d -> dataflows.put(ref(d.agencyId(), d.artefactId(), d.version()), d));
    microdataFlowService
        .findAll()
        .forEach(f -> microdataFlows.put(ref(f.agencyId(), f.artefactId(), f.version()), f));

    List<BulkLoadError> errors = new ArrayList<>();
    List<TargetReport> targets = new ArrayList<>();
    // all targets of a step in one transaction; microdata rows are streamed into staging tables
    // (#17), time series rows are collected per dataflow
    transactions.executeWithoutResult(
        status -> {
          MicrodataStagingLoader.Session micro = microdataLoader.open(errors);
          Map<String, List<ObservationBulkLoader.Row>> observationRows = new LinkedHashMap<>();
          for (Path file : files) {
            String name = outDir.relativize(file).toString();
            try {
              HandoffFileReader.stream(
                  file,
                  new FileRows(name, dataflows, microdataFlows, micro, observationRows, errors));
            } catch (SQLException e) {
              errors.add(new BulkLoadError(name, 0, "Cannot read file: " + e.getMessage()));
            }
            if (errors.size() >= MicrodataStagingLoader.MAX_ERRORS) {
              break;
            }
          }
          if (errors.isEmpty()) {
            for (Map.Entry<String, List<ObservationBulkLoader.Row>> e :
                observationRows.entrySet()) {
              Dataflow df = dataflows.get(e.getKey());
              BulkLoadResult result = observationLoader.load(df.id(), e.getValue(), recordedBy);
              errors.addAll(result.errors());
              targets.add(
                  new TargetReport(
                      "dataflow",
                      df.id(),
                      result.flowRef(),
                      result.rows(),
                      result.written(),
                      result.unchanged()));
            }
          }
          // also with errors: finish() runs the checks across rows, but writes only when valid
          if (errors.size() < MicrodataStagingLoader.MAX_ERRORS) {
            for (MicrodataStagingLoader.FlowResult r : micro.finish(recordedBy)) {
              MicrodataFlow f = r.flow();
              targets.add(
                  new TargetReport(
                      "microdata",
                      f.id(),
                      ref(f.agencyId(), f.artefactId(), f.version()),
                      r.rows(),
                      r.written(),
                      r.unchanged()));
            }
          }
          if (dryRun || !errors.isEmpty()) {
            status.setRollbackOnly();
          }
        });
    return new StepLoadReport(
        dryRun,
        errors.isEmpty() ? targets : List.of(),
        errors.stream().limit(100).map(BulkLoadError::toString).toList(),
        fileNames);
  }

  /** Routes the rows of one file by their STRUCTURE_ID to the time series or microdata loader. */
  private static final class FileRows implements HandoffFileReader.RowHandler {

    private final String name;
    private final Map<String, Dataflow> dataflows;
    private final Map<String, MicrodataFlow> microdataFlows;
    private final MicrodataStagingLoader.Session micro;
    private final Map<String, List<ObservationBulkLoader.Row>> observationRows;
    private final List<BulkLoadError> errors;
    private final Set<String> seenTargets = new HashSet<>();
    private List<String> columns;
    private int structureColumn;
    private int actionColumn;

    FileRows(
        String name,
        Map<String, Dataflow> dataflows,
        Map<String, MicrodataFlow> microdataFlows,
        MicrodataStagingLoader.Session micro,
        Map<String, List<ObservationBulkLoader.Row>> observationRows,
        List<BulkLoadError> errors) {
      this.name = name;
      this.dataflows = dataflows;
      this.microdataFlows = microdataFlows;
      this.micro = micro;
      this.observationRows = observationRows;
      this.errors = errors;
    }

    @Override
    public void columns(List<String> names) {
      columns = names;
      structureColumn = names.indexOf("STRUCTURE_ID");
      if (structureColumn < 0) {
        structureColumn = names.indexOf("DATAFLOW");
      }
      if (structureColumn < 0) {
        errors.add(new BulkLoadError(name, 0, "Missing column STRUCTURE_ID (SDMX-CSV)"));
      }
      actionColumn = names.indexOf("ACTION");
    }

    @Override
    public void row(int rowNumber, List<String> row) {
      if (structureColumn < 0 || errors.size() >= MicrodataStagingLoader.MAX_ERRORS) {
        return;
      }
      String action = actionColumn < 0 ? null : row.get(actionColumn);
      if (action != null && !action.isBlank() && !action.equals("I") && !action.equals("R")) {
        errors.add(
            new BulkLoadError(
                name, rowNumber, "ACTION '" + action + "' is not supported (only I/R)"));
        return;
      }
      String structure = row.get(structureColumn);
      Matcher m = structure == null ? null : REF.matcher(structure.strip());
      if (m == null || !m.matches()) {
        errors.add(new BulkLoadError(name, rowNumber, "Invalid STRUCTURE_ID '" + structure + "'"));
        return;
      }
      String target = ref(m.group(1), m.group(2), m.group(3));
      String group = m.group(4);
      Map<String, String> values = new LinkedHashMap<>();
      // empty values stay in the map: unknown columns are reported even when always empty
      for (int c = 0; c < columns.size(); c++) {
        values.put(columns.get(c), row.get(c));
      }
      MicrodataFlow flow = microdataFlows.get(target);
      if (dataflows.containsKey(target) && group == null) {
        observationRows
            .computeIfAbsent(target, k -> new ArrayList<>())
            .add(new ObservationBulkLoader.Row(name, rowNumber, values));
      } else if (flow != null) {
        if (seenTargets.add(target + "/" + group)) {
          micro.columns(name, flow, group, columns);
        }
        if (group == null) {
          micro.record(name, rowNumber, flow, values);
        } else {
          micro.groupRow(name, rowNumber, flow, group, values);
        }
      } else if (seenTargets.add("unknown:" + target)) {
        errors.add(new BulkLoadError(name, rowNumber, "Unknown flow '" + target + "'"));
      }
    }
  }

  static String ref(String agency, String id, String version) {
    return agency + ":" + id + "(" + version + ")";
  }
}
