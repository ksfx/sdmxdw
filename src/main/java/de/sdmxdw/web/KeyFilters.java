package de.sdmxdw.web;

import de.sdmxdw.structure.dsd.Dimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.MultiValueMap;

/**
 * Key dimension filters of the time series pages (data page, explorer dataflow page): one
 * multi-select per key dimension, request parameter {@code k_<CONCEPT>}. Produces the form model
 * and the selected codes by 1-based key position for {@code ObservationQueryRepository}.
 */
public final class KeyFilters {

  public static final String PARAM_PREFIX = "k_";

  public record Option(String code, String name, String label) {}

  public record Filter(
      String param, String code, String label, List<Option> options, Set<String> selected) {}

  /**
   * @param codesByPosition 1-based key position -> selected codes (only dimensions with a
   *     selection)
   */
  public record Selection(List<Filter> filters, Map<Integer, List<String>> codesByPosition) {

    public boolean active() {
      return !codesByPosition.isEmpty();
    }
  }

  private KeyFilters() {}

  /**
   * @param keyDimensions key dimensions in series key order
   * @param codeNamesByPosition per key dimension: code -> name (empty for uncoded dimensions)
   */
  public static Selection select(
      List<Dimension> keyDimensions,
      List<Map<String, String>> codeNamesByPosition,
      Map<Long, String> conceptCodes,
      Map<Long, String> conceptLabels,
      MultiValueMap<String, String> params) {
    List<Filter> filters = new ArrayList<>();
    Map<Integer, List<String>> codesByPosition = new HashMap<>();
    for (int i = 0; i < keyDimensions.size(); i++) {
      Dimension d = keyDimensions.get(i);
      String code = conceptCodes.getOrDefault(d.conceptId(), "DIM" + (i + 1));
      List<String> selected =
          params.getOrDefault(PARAM_PREFIX + code, List.of()).stream()
              .filter(v -> !v.isBlank())
              .toList();
      if (!selected.isEmpty()) {
        codesByPosition.put(i + 1, selected);
      }
      filters.add(
          new Filter(
              PARAM_PREFIX + code,
              code,
              conceptLabels.getOrDefault(d.conceptId(), code),
              codeNamesByPosition.get(i).entrySet().stream()
                  .map(e -> new Option(e.getKey(), e.getValue(), e.getKey() + " - " + e.getValue()))
                  .toList(),
              Set.copyOf(selected)));
    }
    return new Selection(filters, codesByPosition);
  }
}
