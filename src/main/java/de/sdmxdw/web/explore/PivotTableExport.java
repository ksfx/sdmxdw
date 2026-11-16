package de.sdmxdw.web.explore;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** The table view (#15) as a grid of text cells, for CSV and Excel. */
final class PivotTableExport {

  private PivotTableExport() {}

  /**
   * Header lines (one per column dimension) and data lines: section (if any), the row codes and
   * names, then the cell values.
   */
  static List<List<String>> lines(PivotTableService.PivotTable t) {
    boolean sections = !t.layout().sections().isEmpty();
    int lead = (sections ? 1 : 0) + 2 * t.layout().rows().size();
    List<List<String>> lines = new ArrayList<>();
    for (int level = 0; level < t.columnHeaders().size(); level++) {
      List<String> line = new ArrayList<>();
      for (int i = 0; i < lead; i++) {
        line.add(i == lead - 1 ? t.layout().columns().get(level) : "");
      }
      for (PivotTableService.HeaderCell h : t.columnHeaders().get(level)) {
        for (int k = 0; k < h.colspan(); k++) {
          line.add(h.name() == null ? h.code() : h.code() + " - " + h.name());
        }
      }
      lines.add(line);
    }
    List<String> head = new ArrayList<>();
    if (sections) {
      head.add(String.join(" / ", t.layout().sections()));
    }
    for (String d : t.layout().rows()) {
      head.add(d);
      head.add(d + " (name)");
    }
    for (int i = 0; i < t.columnCount(); i++) {
      head.add("");
    }
    lines.add(head);
    for (PivotTableService.Section s : t.sections()) {
      for (PivotTableService.Row r : s.rows()) {
        List<String> line = new ArrayList<>();
        if (sections) {
          line.add(s.label());
        }
        for (int i = 0; i < r.codes().size(); i++) {
          line.add(r.codes().get(i));
          line.add(r.names().get(i) == null ? "" : r.names().get(i));
        }
        r.cells().forEach(c -> line.add(c.value()));
        lines.add(line);
      }
    }
    return lines;
  }

  static String csv(String value) {
    if (value == null) {
      return "";
    }
    return value.contains(",") || value.contains("\"") || value.contains("\n")
        ? "\"" + value.replace("\"", "\"\"") + "\""
        : value;
  }

  /** Values become numeric cells where they are numbers; codes and names stay text ("01"). */
  static void xlsx(PivotTableService.PivotTable t, OutputStream out) throws IOException {
    int lead = (t.layout().sections().isEmpty() ? 0 : 1) + 2 * t.layout().rows().size();
    int headerLines = t.columnHeaders().size() + 1;
    try (XSSFWorkbook wb = new XSSFWorkbook()) {
      XSSFSheet sheet = wb.createSheet(t.dataflow().artefactId());
      List<List<String>> lines = lines(t);
      for (int i = 0; i < lines.size(); i++) {
        Row row = sheet.createRow(i);
        List<String> line = lines.get(i);
        for (int j = 0; j < line.size(); j++) {
          String v = line.get(j);
          if (i >= headerLines && j >= lead && !v.isEmpty()) {
            try {
              row.createCell(j).setCellValue(Double.parseDouble(v));
              continue;
            } catch (NumberFormatException e) {
              // not a number: text below
            }
          }
          row.createCell(j).setCellValue(v);
        }
      }
      wb.write(out);
    }
  }
}
