package de.sdmxdw.web.rest;

import de.sdmxdw.structure.categorisation.CategorisationService;
import de.sdmxdw.structure.category.CategorySchemeService;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.web.rest.StructureCatalog.Node;
import de.sdmxdw.web.rest.StructureCatalog.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Artefact resolution shared by the SDMX REST endpoints (wildcards, "latest", flow refs). */
@Component
public class SdmxRestSupport {

  /**
   * Agency id under which this instance is registered in SDMX clients (e.g. the source id in Python
   * sdmx1, which uses it as default agency in structure queries). Treated like "all".
   */
  public static final String INSTANCE_AGENCY = "SDMXDW";

  private static final Set<String> WILDCARDS = Set.of("", "all", "*", "~", "latest", "+");

  private final DataflowService dataflowService;
  private final DataStructureDefinitionService dsdService;
  private final CodelistService codelistService;
  private final ConceptSchemeService conceptSchemeService;
  private final CategorySchemeService categorySchemeService;
  private final CategorisationService categorisationService;

  public SdmxRestSupport(
      DataflowService dataflowService,
      DataStructureDefinitionService dsdService,
      CodelistService codelistService,
      ConceptSchemeService conceptSchemeService,
      CategorySchemeService categorySchemeService,
      CategorisationService categorisationService) {
    this.dataflowService = dataflowService;
    this.dsdService = dsdService;
    this.codelistService = codelistService;
    this.conceptSchemeService = conceptSchemeService;
    this.categorySchemeService = categorySchemeService;
    this.categorisationService = categorisationService;
  }

  StructureCatalog loadCatalog() {
    return new StructureCatalog(
        dataflowService.findAll(),
        dsdService.findAll(),
        codelistService.findAll(),
        conceptSchemeService.findAll(),
        categorySchemeService.findAll(),
        categorisationService.findAll());
  }

  /**
   * Matches artefacts of one type. Agency and id accept "all"/"*" and several values separated by
   * "+" (SDMX 2.1) or "," (SDMX 3.0); version "latest"/"~" returns the highest version per
   * artefact, "all"/"*" every version.
   */
  List<Node> match(StructureCatalog catalog, Type type, String agency, String id, String version) {
    return matchStrict(
        catalog, type, INSTANCE_AGENCY.equalsIgnoreCase(agency) ? "all" : agency, id, version);
  }

  private List<Node> matchStrict(
      StructureCatalog catalog, Type type, String agency, String id, String version) {
    List<Node> matched =
        catalog.all(type).stream()
            .filter(
                n -> {
                  String[] t = catalog.triple(n);
                  return matches(agency, t[0])
                      && matches(id, t[1])
                      && versionMatches(version, t[2]);
                })
            .toList();
    if (!isLatest(version)) {
      return matched;
    }
    Map<String, Node> latest = new LinkedHashMap<>();
    for (Node n : matched) {
      String[] t = catalog.triple(n);
      latest.merge(
          t[0] + ":" + t[1],
          n,
          (a, b) -> compareVersions(catalog.triple(a)[2], catalog.triple(b)[2]) >= 0 ? a : b);
    }
    return new ArrayList<>(latest.values());
  }

  /** Resolves a data query flow ref: "ID", "AGENCY,ID" or "AGENCY,ID,VERSION". */
  Node resolveDataflow(StructureCatalog catalog, String agency, String id, String version) {
    List<Node> nodes =
        match(catalog, Type.dataflow, agency, id, version == null ? "latest" : version);
    if (nodes.isEmpty()) {
      throw new NoSuchElementException(
          "Unknown dataflow: " + agency + "," + id + "," + (version == null ? "latest" : version));
    }
    if (nodes.size() > 1) {
      throw new IllegalArgumentException(
          "Dataflow id "
              + id
              + " is ambiguous, specify the agency: "
              + nodes.stream()
                  .map(n -> String.join(",", catalog.triple(n)))
                  .collect(Collectors.joining(" / ")));
    }
    return nodes.getFirst();
  }

  static String[] parseFlowRef(String flowRef) {
    String[] parts = flowRef.split(",", -1);
    return switch (parts.length) {
      case 1 -> new String[] {"all", parts[0], "latest"};
      case 2 -> new String[] {parts[0], parts[1], "latest"};
      case 3 -> parts;
      default -> throw new IllegalArgumentException("Invalid flow reference: " + flowRef);
    };
  }

  static boolean isWildcard(String value) {
    return value == null || WILDCARDS.contains(value.toLowerCase());
  }

  private static boolean isLatest(String version) {
    return version == null
        || version.isEmpty()
        || "latest".equalsIgnoreCase(version)
        || "~".equals(version);
  }

  private static boolean matches(String requested, String actual) {
    if (isWildcard(requested)) {
      return true;
    }
    return Arrays.stream(requested.split("[+,]")).anyMatch(r -> r.equals(actual));
  }

  private static boolean versionMatches(String requested, String actual) {
    return isWildcard(requested) || matches(requested, actual);
  }

  static int compareVersions(String a, String b) {
    Comparator<String> numeric =
        (x, y) -> {
          String[] xs = x.split("\\.");
          String[] ys = y.split("\\.");
          for (int i = 0; i < Math.max(xs.length, ys.length); i++) {
            int xi = i < xs.length ? parse(xs[i]) : 0;
            int yi = i < ys.length ? parse(ys[i]) : 0;
            if (xi != yi) {
              return Integer.compare(xi, yi);
            }
          }
          return x.compareTo(y);
        };
    return numeric.compare(a, b);
  }

  private static int parse(String part) {
    try {
      return Integer.parseInt(part.replaceAll("[^0-9].*$", ""));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
