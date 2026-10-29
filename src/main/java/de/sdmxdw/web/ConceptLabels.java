package de.sdmxdw.web;

import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import java.util.HashMap;
import java.util.Map;

public final class ConceptLabels {

  private ConceptLabels() {}

  public static Map<Long, String> byConceptId(ConceptSchemeService conceptSchemeService) {
    Map<Long, String> labels = new HashMap<>();
    for (ConceptScheme scheme : conceptSchemeService.findAll()) {
      scheme
          .concepts()
          .forEach(
              concept ->
                  labels.put(
                      concept.id(),
                      scheme.artefactId() + ":" + concept.code() + " - " + concept.name()));
    }
    return labels;
  }
}
