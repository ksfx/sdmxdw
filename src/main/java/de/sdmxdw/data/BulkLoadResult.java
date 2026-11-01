package de.sdmxdw.data;

import java.util.List;

/**
 * Outcome of loading one delivery into one flow.
 *
 * @param errors validation errors; nothing is written if there are any
 * @param written new vintages (new or changed values)
 * @param unchanged re-delivered values identical to the current Alpha value
 */
public record BulkLoadResult(
    String flowRef, int rows, int written, int unchanged, List<BulkLoadError> errors) {

  public boolean valid() {
    return errors.isEmpty();
  }
}
