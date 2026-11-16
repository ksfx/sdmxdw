package de.sdmxdw.web.explore;

import de.sdmxdw.structure.codelist.Code;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataType;
import de.sdmxdw.structure.dsd.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Concepts and the meaning of a series key, for the series pages and the series exports. */
@Component
public class SeriesStructure {

  public record ConceptRef(String code, String name, Long schemeId, String schemeArtefactId) {
    String label() {
      return schemeArtefactId + ":" + code + " - " + name;
    }
  }

  public record DimensionInfo(
      String conceptCode,
      String conceptName,
      Long conceptSchemeId,
      String value,
      String valueName,
      Long codelistId,
      String codelistArtefactId) {}

  private final ConceptSchemeService conceptSchemeService;
  private final CodelistService codelistService;

  public SeriesStructure(
      ConceptSchemeService conceptSchemeService, CodelistService codelistService) {
    this.conceptSchemeService = conceptSchemeService;
    this.codelistService = codelistService;
  }

  public Map<Long, ConceptRef> conceptRefs() {
    Map<Long, ConceptRef> refs = new HashMap<>();
    for (ConceptScheme scheme : conceptSchemeService.findAll()) {
      scheme
          .concepts()
          .forEach(
              c ->
                  refs.put(
                      c.id(),
                      new ConceptRef(c.code(), c.name(), scheme.id(), scheme.artefactId())));
    }
    return refs;
  }

  /** Concept, code and code name of every key dimension of the series key. */
  public List<DimensionInfo> keyDimensionInfos(
      DataStructureDefinition dsd, String seriesKey, Map<Long, ConceptRef> concepts) {
    String[] values = seriesKey.split("\\.", -1);
    List<Dimension> keyDimensions =
        dsd.dimensions().stream()
            .filter(d -> d.dataType() != DataType.DATE)
            .sorted(Comparator.comparingInt(Dimension::position))
            .toList();
    List<DimensionInfo> infos = new ArrayList<>();
    for (int i = 0; i < keyDimensions.size(); i++) {
      Dimension dimension = keyDimensions.get(i);
      String value = i < values.length ? values[i] : "";
      ConceptRef concept = concepts.get(dimension.conceptId());
      Codelist codelist =
          dimension.codelistId() == null ? null : codelistService.findById(dimension.codelistId());
      String valueName =
          codelist == null
              ? null
              : codelist.codes().stream()
                  .filter(c -> c.code().equals(value))
                  .map(Code::name)
                  .findFirst()
                  .orElse(null);
      infos.add(
          new DimensionInfo(
              concept == null ? "#" + dimension.conceptId() : concept.code(),
              concept == null ? "" : concept.name(),
              concept == null ? null : concept.schemeId(),
              value,
              valueName,
              codelist == null ? null : codelist.id(),
              codelist == null ? null : codelist.artefactId()));
    }
    return infos;
  }
}
