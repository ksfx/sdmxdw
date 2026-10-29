package de.sdmxdw.process;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a handed-over file (SDMX-CSV or Parquet) into string columns, using an in-memory DuckDB.
 * Typed Parquet values are normalised to the text form the loaders expect: numbers without trailing
 * zeros ({@code 640000.0} becomes {@code 640000}), booleans as true/false, dates ISO.
 */
final class HandoffFileReader {

  private HandoffFileReader() {}

  record Table(List<String> columns, List<List<String>> rows) {}

  static boolean supported(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".csv") || name.endsWith(".parquet");
  }

  static Table read(Path file) throws SQLException {
    String path = file.toAbsolutePath().toString().replace("'", "''");
    String source =
        file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".parquet")
            ? "read_parquet('" + path + "')"
            : "read_csv('"
                + path
                + "', header = true, all_varchar = true, delim = ',',"
                + " quote = '\"', escape = '\"')";
    try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
        Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery("SELECT * FROM " + source)) {
      ResultSetMetaData meta = rs.getMetaData();
      List<String> columns = new ArrayList<>();
      for (int i = 1; i <= meta.getColumnCount(); i++) {
        columns.add(meta.getColumnName(i).strip());
      }
      List<List<String>> rows = new ArrayList<>();
      while (rs.next()) {
        List<String> row = new ArrayList<>(columns.size());
        for (int i = 1; i <= columns.size(); i++) {
          row.add(text(rs.getObject(i)));
        }
        rows.add(row);
      }
      return new Table(columns, rows);
    }
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        return null;
      }
      BigDecimal decimal = value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
      String plain = decimal.stripTrailingZeros().toPlainString();
      return plain.equals("-0") ? "0" : plain;
    }
    return value.toString();
  }
}
