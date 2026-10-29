package de.sdmxdw.web.rest;

import de.sdmxdw.microdata.FieldDataType;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;

/**
 * Row-by-row writers for microdata tables: SDMX-CSV, JSON and Parquet. CSV and JSON stream directly
 * to the response; Parquet rows are appended to an in-memory DuckDB table and written with DuckDB's
 * Parquet writer, which gives typed columns (numbers, dates, booleans) without the Hadoop
 * dependency stack of parquet-java.
 */
abstract class MicrodataTableWriter implements AutoCloseable {

  record Column(String name, FieldDataType type) {}

  enum Format {
    CSV("application/vnd.sdmx.data+csv;version=2.0.0;charset=UTF-8", "csv"),
    JSON("application/json;charset=UTF-8", "json"),
    PARQUET("application/vnd.apache.parquet", "parquet");

    final String mediaType;
    final String extension;

    Format(String mediaType, String extension) {
      this.mediaType = mediaType;
      this.extension = extension;
    }
  }

  protected final List<Column> columns;

  MicrodataTableWriter(List<Column> columns) {
    this.columns = columns;
  }

  /**
   * @param values aligned with the columns; null = no value
   */
  abstract void row(List<String> values);

  @Override
  public abstract void close();

  static MicrodataTableWriter create(
      Format format,
      List<Column> columns,
      String structureId,
      ObjectMapper json,
      OutputStream out) {
    return switch (format) {
      case CSV -> new Csv(columns, structureId, out);
      case JSON -> new Json(columns, structureId, json, out);
      case PARQUET -> new Parquet(columns, out);
    };
  }

  /** SDMX-CSV 2.0 layout: STRUCTURE, STRUCTURE_ID, ACTION, then one column per component. */
  private static final class Csv extends MicrodataTableWriter {
    private final Writer writer;
    private final String prefix;
    private final StringBuilder line = new StringBuilder();

    Csv(List<Column> columns, String structureId, OutputStream out) {
      super(columns);
      this.writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
      this.prefix = "dataflow," + SdmxDataWriter.csv(structureId) + ",I";
      write(
          "STRUCTURE,STRUCTURE_ID,ACTION,"
              + columns.stream()
                  .map(c -> SdmxDataWriter.csv(c.name()))
                  .collect(Collectors.joining(","))
              + "\n");
    }

    @Override
    void row(List<String> values) {
      line.setLength(0);
      line.append(prefix);
      values.forEach(v -> line.append(',').append(SdmxDataWriter.csv(v)));
      write(line.append('\n').toString());
    }

    private void write(String s) {
      try {
        writer.write(s);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void close() {
      try {
        writer.flush();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /** {"structure": "...", "columns": [...], "data": [{...}, ...]} with typed values. */
  private static final class Json extends MicrodataTableWriter {
    private final JsonGenerator gen;

    Json(List<Column> columns, String structureId, ObjectMapper mapper, OutputStream out) {
      super(columns);
      this.gen = mapper.createGenerator(out);
      gen.writeStartObject();
      gen.writeStringProperty("structure", structureId);
      gen.writeName("columns");
      gen.writeStartArray();
      for (Column c : columns) {
        gen.writeStartObject();
        gen.writeStringProperty("name", c.name());
        gen.writeStringProperty("type", c.type().name());
        gen.writeEndObject();
      }
      gen.writeEndArray();
      gen.writeName("data");
      gen.writeStartArray();
    }

    @Override
    void row(List<String> values) {
      gen.writeStartObject();
      for (int i = 0; i < columns.size(); i++) {
        gen.writeName(columns.get(i).name());
        String v = values.get(i);
        if (v == null || v.isEmpty()) {
          gen.writeNull();
          continue;
        }
        switch (columns.get(i).type()) {
          case NUMBER -> {
            try {
              gen.writeNumber(new BigDecimal(v));
            } catch (NumberFormatException e) {
              gen.writeString(v);
            }
          }
          case BOOLEAN -> {
            if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
              gen.writeBoolean(Boolean.parseBoolean(v));
            } else {
              gen.writeString(v);
            }
          }
          default -> gen.writeString(v);
        }
      }
      gen.writeEndObject();
    }

    @Override
    public void close() {
      gen.writeEndArray();
      gen.writeEndObject();
      gen.close();
    }
  }

  private static final class Parquet extends MicrodataTableWriter {
    private final OutputStream out;
    private final Connection connection;
    private final DuckDBAppender appender;

    Parquet(List<Column> columns, OutputStream out) {
      super(columns);
      this.out = out;
      try {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = connection.createStatement()) {
          // load everything as text first, typed conversion happens in the COPY below
          st.execute(
              "CREATE TABLE t ("
                  + columns.stream()
                      .map(c -> quote(c.name()) + " VARCHAR")
                      .collect(Collectors.joining(", "))
                  + ")");
        }
        appender =
            connection
                .unwrap(DuckDBConnection.class)
                .createAppender(DuckDBConnection.DEFAULT_SCHEMA, "t");
      } catch (SQLException e) {
        throw new IllegalStateException("Cannot initialise Parquet writer", e);
      }
    }

    @Override
    void row(List<String> values) {
      try {
        appender.beginRow();
        for (String v : values) {
          if (v == null) {
            appender.appendNull();
          } else {
            appender.append(v);
          }
        }
        appender.endRow();
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void close() {
      Path file = null;
      try {
        appender.close();
        file = Files.createTempFile("sdmxdw-", ".parquet");
        String select =
            columns.stream()
                .map(c -> cast(c) + " AS " + quote(c.name()))
                .collect(Collectors.joining(", "));
        try (Statement st = connection.createStatement()) {
          st.execute(
              "COPY (SELECT "
                  + select
                  + " FROM t) TO '"
                  + file.toString().replace("'", "''")
                  + "' (FORMAT parquet, COMPRESSION zstd)");
        }
        Files.copy(file, out);
        out.flush();
      } catch (SQLException | IOException e) {
        throw new IllegalStateException("Cannot write Parquet", e);
      } finally {
        try {
          connection.close();
          if (file != null) {
            Files.deleteIfExists(file);
          }
        } catch (SQLException | IOException ignored) {
          // temp file cleanup is best effort
        }
      }
    }

    private static String cast(Column c) {
      String col = quote(c.name());
      return switch (c.type()) {
        case NUMBER -> "TRY_CAST(" + col + " AS DOUBLE)";
        case DATE -> "TRY_CAST(" + col + " AS DATE)";
        case BOOLEAN -> "TRY_CAST(" + col + " AS BOOLEAN)";
        case STRING -> col;
      };
    }

    private static String quote(String name) {
      return '"' + name.replace("\"", "\"\"") + '"';
    }
  }
}
