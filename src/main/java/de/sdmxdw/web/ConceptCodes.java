package de.sdmxdw.web;

import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import java.util.HashMap;
import java.util.Map;

public final class ConceptCodes {

  private ConceptCodes() {}

  public static Map<Long, String> byConceptId(ConceptSchemeService conceptSchemeService) {
    Map<Long, String> codes = new HashMap<>();
    for (ConceptScheme scheme : conceptSchemeService.findAll()) {
      scheme.concepts().forEach(concept -> codes.put(concept.id(), concept.code()));
    }
    return codes;
  }
}
