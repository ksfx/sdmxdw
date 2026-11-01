package de.sdmxdw.data;

/** One problem in a delivered file; {@code row} is 1-based and counts data rows (0 = file). */
public record BulkLoadError(String file, int row, String message) {

  @Override
  public String toString() {
    return file + (row > 0 ? " row " + row : "") + ": " + message;
  }
}
