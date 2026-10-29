package de.sdmxdw.web.explore;

import java.util.List;

/**
 * JSON model of the facet pickers ({@code static/js/dimension-filter.js}), shared by the time
 * series dataflow page (key dimensions) and the microdata flow page (indexed fields).
 */
public final class Facets {

  private Facets() {}

  /**
   * @param total number of series / records matching the whole selection
   */
  public record Response(long total, List<Dimension> dimensions) {}

  /**
   * @param param request parameter carrying the selection, e.g. {@code k_FREQ} or {@code f_CCY}
   */
  public record Dimension(
      String param, String code, String label, List<Value> values, List<String> selected) {}

  public record Value(String code, String name, long count) {}
}
