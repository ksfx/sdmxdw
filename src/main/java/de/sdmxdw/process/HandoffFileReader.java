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
 * Streams a handed-over file (SDMX-CSV or Parquet) as string columns, using an in-memory DuckDB.
 * Typed Parquet values are normalised to the text form the loaders expect: numbers without trailing
 * zeros ({@code 640000.0} becomes {@code 640000}), booleans as true/false, dates ISO.
 */
final class HandoffFileReader {

  private HandoffFileReader() {}

  static boolean supported(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".csv") || name.endsWith(".parquet");
  }

  /** Receives the column names once, then every row. */
  interface RowHandler {
    void columns(List<String> columns);

    void row(int rowNumber, List<String> values);
  }

  /** Streams the rows of a file without keeping them in memory. */
  static void stream(Path file, RowHandler handler) throws SQLException {
    String path = file.toAbsolutePath().toString().replace("'", "''");
    String source =
        file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".parquet")
            ? "read_parquet('" + path + "')"
            : "read_csv('"
                + path
                + "', header = true, all_varchar = true, delim = ',',"
                + " quote = '\"', escape = '\"')";
    try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
        Statement st = connection.createStatement()) {
      // DuckDB takes up to 80 % of the machine's memory by default, outside the Java heap
      st.execute("SET memory_limit = '256MB'");
      st.execute("SET threads = 2");
      st.setFetchSize(2048);
      try (ResultSet rs = st.executeQuery("SELECT * FROM " + source)) {
        ResultSetMetaData meta = rs.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          columns.add(meta.getColumnName(i).strip());
        }
        handler.columns(columns);
        int rowNumber = 0;
        while (rs.next()) {
          List<String> row = new ArrayList<>(columns.size());
          for (int i = 1; i <= columns.size(); i++) {
            row.add(text(rs.getObject(i)));
          }
          handler.row(++rowNumber, row);
        }
      }
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
