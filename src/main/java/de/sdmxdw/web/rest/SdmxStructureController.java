package de.sdmxdw.web.rest;

import de.sdmxdw.web.rest.StructureCatalog.Node;
import de.sdmxdw.web.rest.StructureCatalog.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SDMX REST structure queries, so standard clients (Python sdmx1, R rsdmx, .Stat, ...) can discover
 * dataflows, their DSDs and codelists before fetching data.
 *
 * <ul>
 *   <li>SDMX 2.1 style: {@code /rest/{type}/{agency}/{id}/{version}?references=...}
 *   <li>SDMX 3.0 style: {@code /rest/structure/{type}/{agency}/{id}/{version}?references=...}
 * </ul>
 *
 * Output is SDMX-ML 2.1 for the 2.1 paths and SDMX-ML 3.0 for the 3.0 paths, unless the Accept
 * header or {@code sdmxVersion=2.1|3.0} asks for the other one.
 */
@RestController
@RequestMapping("/rest")
public class SdmxStructureController {

  static final String TYPES =
      "dataflow|datastructure|codelist|conceptscheme|categoryscheme|categorisation";

  private final SdmxRestSupport support;

  public SdmxStructureController(SdmxRestSupport support) {
    this.support = support;
  }

  @GetMapping({
    "/{type:" + TYPES + "}",
    "/{type:" + TYPES + "}/{agency}",
    "/{type:" + TYPES + "}/{agency}/{id}",
    "/{type:" + TYPES + "}/{agency}/{id}/{version}"
  })
  public ResponseEntity<String> structure21(
      @PathVariable String type,
      @PathVariable(required = false) String agency,
      @PathVariable(required = false) String id,
      @PathVariable(required = false) String version,
      @RequestParam(required = false) String references,
      @RequestParam(required = false) String sdmxVersion,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
    return query(type, agency, id, version, references, version(sdmxVersion, accept, false));
  }

  @GetMapping({
    "/structure/{type}",
    "/structure/{type}/{agency}",
    "/structure/{type}/{agency}/{id}",
    "/structure/{type}/{agency}/{id}/{version}"
  })
  public ResponseEntity<String> structure30(
      @PathVariable String type,
      @PathVariable(required = false) String agency,
      @PathVariable(required = false) String id,
      @PathVariable(required = false) String version,
      @RequestParam(required = false) String references,
      @RequestParam(required = false) String sdmxVersion,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
    return query(type, agency, id, version, references, version(sdmxVersion, accept, true));
  }

  private ResponseEntity<String> query(
      String type,
      String agency,
      String id,
      String version,
      String references,
      SdmxStructureWriter.Version outputVersion) {
    StructureCatalog catalog = support.loadCatalog();
    List<Type> types =
        SdmxRestSupport.isWildcard(type) || "structure".equalsIgnoreCase(type)
            ? List.of(Type.values())
            : List.of(StructureCatalog.parseType(type));
    List<Node> matched = new ArrayList<>();
    for (Type t : types) {
      matched.addAll(support.match(catalog, t, agency, id, version));
    }
    if (matched.isEmpty()) {
      throw new NoSuchElementException("No results found");
    }
    Set<Node> nodes = catalog.withReferences(matched, references);
    String body = SdmxStructureWriter.write(catalog, nodes, outputVersion, Instant.now());
    String mediaType =
        outputVersion == SdmxStructureWriter.Version.V2_1
            ? "application/vnd.sdmx.structure+xml;version=2.1"
            : "application/vnd.sdmx.structure+xml;version=3.0.0";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, mediaType + ";charset=UTF-8")
        .body(body);
  }

  private static SdmxStructureWriter.Version version(
      String sdmxVersion, String accept, boolean v3Path) {
    if (sdmxVersion != null) {
      return sdmxVersion.startsWith("2")
          ? SdmxStructureWriter.Version.V2_1
          : SdmxStructureWriter.Version.V3_0;
    }
    if (accept != null && accept.contains("structure+xml")) {
      return accept.contains("version=2.1")
          ? SdmxStructureWriter.Version.V2_1
          : SdmxStructureWriter.Version.V3_0;
    }
    return v3Path ? SdmxStructureWriter.Version.V3_0 : SdmxStructureWriter.Version.V2_1;
  }
}
