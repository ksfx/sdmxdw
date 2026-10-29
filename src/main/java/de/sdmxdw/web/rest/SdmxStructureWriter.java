package de.sdmxdw.web.rest;

import de.sdmxdw.structure.categorisation.Categorisation;
import de.sdmxdw.structure.category.Category;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.codelist.Code;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.conceptscheme.Concept;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataType;
import de.sdmxdw.structure.dsd.Dimension;
import de.sdmxdw.structure.dsd.Measure;
import de.sdmxdw.web.rest.StructureCatalog.Node;
import de.sdmxdw.web.rest.StructureCatalog.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes an SDMX-ML Structure message (2.1 or 3.0) for a set of catalog artefacts. The main
 * differences between the versions: 2.1 references other artefacts with {@code <Ref>} elements and
 * allows a single primary measure (id OBS_VALUE, further measures become observation-level
 * attributes), 3.0 references by URN text and supports several measures directly.
 */
final class SdmxStructureWriter {

  enum Version {
    V2_1("v2_1"),
    V3_0("v3_0");

    final String schemaPath;

    Version(String schemaPath) {
      this.schemaPath = schemaPath;
    }
  }

  static final String PRIMARY_MEASURE_21 = "OBS_VALUE";

  private final StructureCatalog catalog;
  private final Version version;
  private final StringBuilder xml = new StringBuilder();

  private SdmxStructureWriter(StructureCatalog catalog, Version version) {
    this.catalog = catalog;
    this.version = version;
  }

  static String write(StructureCatalog catalog, Set<Node> nodes, Version version, Instant now) {
    return new SdmxStructureWriter(catalog, version).message(nodes, now);
  }

  private String message(Set<Node> nodes, Instant now) {
    String ns = "http://www.sdmx.org/resources/sdmxml/schemas/" + version.schemaPath;
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        .append("<mes:Structure xmlns:mes=\"")
        .append(ns)
        .append("/message\" xmlns:str=\"")
        .append(ns)
        .append("/structure\" xmlns:com=\"")
        .append(ns)
        .append("/common\" xmlns:xml=\"http://www.w3.org/XML/1998/namespace\">\n");
    xml.append("  <mes:Header>\n    <mes:ID>SDMXDW_")
        .append(now.toEpochMilli())
        .append("</mes:ID>\n    <mes:Test>false</mes:Test>\n    <mes:Prepared>")
        .append(now.truncatedTo(ChronoUnit.SECONDS))
        .append("</mes:Prepared>\n    <mes:Sender id=\"SDMXDW\"/>\n  </mes:Header>\n");
    xml.append("  <mes:Structures>\n");
    // element order as required by the respective schema
    List<Type> order =
        version == Version.V2_1
            ? List.of(
                Type.dataflow,
                Type.categoryscheme,
                Type.categorisation,
                Type.codelist,
                Type.conceptscheme,
                Type.datastructure)
            : List.of(
                Type.categorisation,
                Type.categoryscheme,
                Type.codelist,
                Type.conceptscheme,
                Type.dataflow,
                Type.datastructure);
    for (Type type : order) {
      List<Long> ids = nodes.stream().filter(n -> n.type() == type).map(Node::id).sorted().toList();
      if (ids.isEmpty()) {
        continue;
      }
      String wrapper = wrapper(type);
      xml.append("    <str:").append(wrapper).append(">\n");
      for (Long id : ids) {
        switch (type) {
          case dataflow -> dataflow(catalog.dataflows.get(id));
          case datastructure -> dsd(catalog.dsds.get(id));
          case codelist -> codelist(catalog.codelists.get(id));
          case conceptscheme -> conceptScheme(catalog.conceptSchemes.get(id));
          case categoryscheme -> categoryScheme(catalog.categorySchemes.get(id));
          case categorisation -> categorisation(catalog.categorisations.get(id));
        }
      }
      xml.append("    </str:").append(wrapper).append(">\n");
    }
    xml.append("  </mes:Structures>\n</mes:Structure>\n");
    return xml.toString();
  }

  private String wrapper(Type type) {
    return switch (type) {
      case dataflow -> "Dataflows";
      case datastructure -> "DataStructures";
      case codelist -> "Codelists";
      case conceptscheme -> version == Version.V2_1 ? "Concepts" : "ConceptSchemes";
      case categoryscheme -> "CategorySchemes";
      case categorisation -> "Categorisations";
    };
  }

  // --- maintainables -------------------------------------------------------------------------

  private void codelist(Codelist cl) {
    String urn = urn("codelist.Codelist", cl.agencyId(), cl.artefactId(), cl.version());
    open("Codelist", cl.agencyId(), cl.artefactId(), cl.version(), urn, cl.name());
    cl.codes().stream()
        .sorted(Comparator.comparing(Code::code))
        .forEach(
            c -> {
              xml.append("        <str:Code id=\"")
                  .append(esc(c.code()))
                  .append("\" urn=\"")
                  .append(esc(urn.replace(".Codelist=", ".Code=") + "." + c.code()))
                  .append("\">");
              name(c.name());
              xml.append("</str:Code>\n");
            });
    close("Codelist");
  }

  private void conceptScheme(ConceptScheme cs) {
    String urn = urn("conceptscheme.ConceptScheme", cs.agencyId(), cs.artefactId(), cs.version());
    open("ConceptScheme", cs.agencyId(), cs.artefactId(), cs.version(), urn, cs.name());
    cs.concepts().stream()
        .sorted(Comparator.comparing(Concept::code))
        .forEach(
            c -> {
              xml.append("        <str:Concept id=\"")
                  .append(esc(c.code()))
                  .append("\" urn=\"")
                  .append(esc(conceptUrn(c.id())))
                  .append("\">");
              name(c.name());
              xml.append("</str:Concept>\n");
            });
    close("ConceptScheme");
  }

  private void categoryScheme(CategoryScheme cs) {
    String urn = urn("categoryscheme.CategoryScheme", cs.agencyId(), cs.artefactId(), cs.version());
    open("CategoryScheme", cs.agencyId(), cs.artefactId(), cs.version(), urn, cs.name());
    Map<Long, List<Category>> byParent =
        cs.categories().stream()
            .collect(
                Collectors.groupingBy(
                    c -> c.parentCategoryId() == null ? -1L : c.parentCategoryId()));
    categories(byParent, -1L, urn.replace(".CategoryScheme=", ".Category=") + ".", "        ");
    close("CategoryScheme");
  }

  private void categories(
      Map<Long, List<Category>> byParent, Long parent, String urnPrefix, String indent) {
    for (Category c :
        byParent.getOrDefault(parent, List.of()).stream()
            .sorted(Comparator.comparing(Category::code))
            .toList()) {
      String urn = urnPrefix + c.code();
      xml.append(indent)
          .append("<str:Category id=\"")
          .append(esc(c.code()))
          .append("\" urn=\"")
          .append(esc(urn))
          .append("\">");
      name(c.name());
      xml.append('\n');
      categories(byParent, c.id(), urn + ".", indent + "  ");
      xml.append(indent).append("</str:Category>\n");
    }
  }

  private void dataflow(Dataflow df) {
    String urn = urn("datastructure.Dataflow", df.agencyId(), df.artefactId(), df.version());
    open("Dataflow", df.agencyId(), df.artefactId(), df.version(), urn, df.name());
    DataStructureDefinition dsd = catalog.dsds.get(df.dsdId());
    xml.append("        <str:Structure>");
    if (version == Version.V2_1) {
      ref(dsd.agencyId(), dsd.artefactId(), dsd.version(), "datastructure", "DataStructure");
    } else {
      xml.append(
          urn("datastructure.DataStructure", dsd.agencyId(), dsd.artefactId(), dsd.version()));
    }
    xml.append("</str:Structure>\n");
    close("Dataflow");
  }

  private void categorisation(Categorisation c) {
    Dataflow df = catalog.dataflows.get(c.dataflowId());
    CategoryScheme scheme = catalog.categorySchemeOf(c.categoryId());
    String id = catalog.categorisationId(c);
    String urn = urn("categoryscheme.Categorisation", df.agencyId(), id, "1.0");
    open(
        "Categorisation",
        df.agencyId(),
        id,
        "1.0",
        urn,
        df.artefactId() + " in " + scheme.artefactId());
    String path = String.join(".", catalog.categoryPath(c.categoryId()));
    if (version == Version.V2_1) {
      xml.append("        <str:Source>");
      ref(df.agencyId(), df.artefactId(), df.version(), "datastructure", "Dataflow");
      xml.append("</str:Source>\n        <str:Target><Ref id=\"")
          .append(esc(path))
          .append("\" maintainableParentID=\"")
          .append(esc(scheme.artefactId()))
          .append("\" maintainableParentVersion=\"")
          .append(esc(scheme.version()))
          .append("\" agencyID=\"")
          .append(esc(scheme.agencyId()))
          .append("\" package=\"categoryscheme\" class=\"Category\"/></str:Target>\n");
    } else {
      xml.append("        <str:Source>")
          .append(urn("datastructure.Dataflow", df.agencyId(), df.artefactId(), df.version()))
          .append("</str:Source>\n        <str:Target>")
          .append(
              esc(
                  urn(
                          "categoryscheme.Category",
                          scheme.agencyId(),
                          scheme.artefactId(),
                          scheme.version())
                      + "."
                      + path))
          .append("</str:Target>\n");
    }
    close("Categorisation");
  }

  private void dsd(DataStructureDefinition dsd) {
    String urn =
        urn("datastructure.DataStructure", dsd.agencyId(), dsd.artefactId(), dsd.version());
    String componentUrn = urn.replace(".DataStructure=", ".%s=") + ".%s";
    open("DataStructure", dsd.agencyId(), dsd.artefactId(), dsd.version(), urn, dsd.name());
    xml.append("        <str:DataStructureComponents>\n");
    xml.append("          <str:DimensionList id=\"DimensionDescriptor\" urn=\"")
        .append(esc(componentUrn.formatted("DimensionDescriptor", "DimensionDescriptor")))
        .append("\">\n");
    List<Dimension> dims =
        dsd.dimensions().stream().sorted(Comparator.comparingInt(Dimension::position)).toList();
    for (Dimension d : dims) {
      String code = catalog.concept(d.conceptId()).code();
      boolean time = d.dataType() == DataType.DATE;
      String element = time ? "TimeDimension" : "Dimension";
      xml.append("            <str:")
          .append(element)
          .append(" id=\"")
          .append(esc(code))
          .append("\" position=\"")
          .append(d.position())
          .append("\" urn=\"")
          .append(esc(componentUrn.formatted(element, code)))
          .append("\">\n");
      conceptIdentity(d.conceptId());
      xml.append("              <str:LocalRepresentation>");
      if (time) {
        xml.append("<str:TextFormat textType=\"ObservationalTimePeriod\"/>");
      } else if (d.codelistId() != null) {
        Codelist cl = catalog.codelists.get(d.codelistId());
        xml.append("<str:Enumeration>");
        if (version == Version.V2_1) {
          ref(cl.agencyId(), cl.artefactId(), cl.version(), "codelist", "Codelist");
        } else {
          xml.append(esc(urn("codelist.Codelist", cl.agencyId(), cl.artefactId(), cl.version())));
        }
        xml.append("</str:Enumeration>");
      } else {
        textFormat(d.dataType());
      }
      xml.append("</str:LocalRepresentation>\n            </str:").append(element).append(">\n");
    }
    xml.append("          </str:DimensionList>\n");

    List<Measure> measures =
        dsd.measures().stream().sorted(Comparator.comparing(Measure::id)).toList();
    if (version == Version.V2_1) {
      // 2.1 knows exactly one primary measure; further measures become obs attributes
      if (measures.size() > 1) {
        xml.append("          <str:AttributeList id=\"AttributeDescriptor\" urn=\"")
            .append(esc(componentUrn.formatted("AttributeDescriptor", "AttributeDescriptor")))
            .append("\">\n");
        for (Measure m : measures.subList(1, measures.size())) {
          String code = catalog.concept(m.conceptId()).code();
          xml.append("            <str:Attribute id=\"")
              .append(esc(code))
              .append("\" assignmentStatus=\"Conditional\" urn=\"")
              .append(esc(componentUrn.formatted("DataAttribute", code)))
              .append("\">\n");
          conceptIdentity(m.conceptId());
          xml.append("              <str:LocalRepresentation>");
          textFormat(m.dataType());
          xml.append("</str:LocalRepresentation>\n")
              .append("              <str:AttributeRelationship><str:PrimaryMeasure><Ref id=\"")
              .append(PRIMARY_MEASURE_21)
              .append("\"/></str:PrimaryMeasure></str:AttributeRelationship>\n")
              .append("            </str:Attribute>\n");
        }
        xml.append("          </str:AttributeList>\n");
      }
      xml.append("          <str:MeasureList id=\"MeasureDescriptor\" urn=\"")
          .append(esc(componentUrn.formatted("MeasureDescriptor", "MeasureDescriptor")))
          .append("\">\n");
      if (!measures.isEmpty()) {
        Measure primary = measures.getFirst();
        xml.append("            <str:PrimaryMeasure id=\"")
            .append(PRIMARY_MEASURE_21)
            .append("\" urn=\"")
            .append(esc(componentUrn.formatted("PrimaryMeasure", PRIMARY_MEASURE_21)))
            .append("\">\n");
        conceptIdentity(primary.conceptId());
        xml.append("              <str:LocalRepresentation>");
        textFormat(primary.dataType());
        xml.append("</str:LocalRepresentation>\n            </str:PrimaryMeasure>\n");
      }
      xml.append("          </str:MeasureList>\n");
    } else {
      xml.append("          <str:MeasureList id=\"MeasureDescriptor\" urn=\"")
          .append(esc(componentUrn.formatted("MeasureDescriptor", "MeasureDescriptor")))
          .append("\">\n");
      for (Measure m : measures) {
        String code = catalog.concept(m.conceptId()).code();
        xml.append("            <str:Measure id=\"")
            .append(esc(code))
            .append("\" urn=\"")
            .append(esc(componentUrn.formatted("Measure", code)))
            .append("\">\n");
        conceptIdentity(m.conceptId());
        xml.append("              <str:LocalRepresentation>");
        textFormat(m.dataType());
        xml.append("</str:LocalRepresentation>\n            </str:Measure>\n");
      }
      xml.append("          </str:MeasureList>\n");
    }
    xml.append("        </str:DataStructureComponents>\n");
    close("DataStructure");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void open(String element, String agency, String id, String ver, String urn, String name) {
    xml.append("      <str:")
        .append(element)
        .append(" id=\"")
        .append(esc(id))
        .append("\" agencyID=\"")
        .append(esc(agency))
        .append("\" version=\"")
        .append(esc(ver))
        .append("\" urn=\"")
        .append(esc(urn))
        .append('"');
    if (version == Version.V2_1) {
      xml.append(" isFinal=\"false\"");
    }
    xml.append(">\n        ");
    name(name);
    xml.append('\n');
  }

  private void close(String element) {
    xml.append("      </str:").append(element).append(">\n");
  }

  private void name(String name) {
    xml.append("<com:Name xml:lang=\"en\">")
        .append(esc(name == null || name.isBlank() ? "-" : name))
        .append("</com:Name>");
  }

  private void ref(String agency, String id, String ver, String pkg, String cls) {
    xml.append("<Ref id=\"")
        .append(esc(id))
        .append("\" version=\"")
        .append(esc(ver))
        .append("\" agencyID=\"")
        .append(esc(agency))
        .append("\" package=\"")
        .append(pkg)
        .append("\" class=\"")
        .append(cls)
        .append("\"/>");
  }

  private void conceptIdentity(Long conceptId) {
    xml.append("              <str:ConceptIdentity>");
    if (version == Version.V2_1) {
      ConceptScheme cs = catalog.conceptSchemeOf(conceptId);
      xml.append("<Ref id=\"")
          .append(esc(catalog.concept(conceptId).code()))
          .append("\" maintainableParentID=\"")
          .append(esc(cs.artefactId()))
          .append("\" maintainableParentVersion=\"")
          .append(esc(cs.version()))
          .append("\" agencyID=\"")
          .append(esc(cs.agencyId()))
          .append("\" package=\"conceptscheme\" class=\"Concept\"/>");
    } else {
      xml.append(esc(conceptUrn(conceptId)));
    }
    xml.append("</str:ConceptIdentity>\n");
  }

  private String conceptUrn(Long conceptId) {
    ConceptScheme cs = catalog.conceptSchemeOf(conceptId);
    return urn("conceptscheme.Concept", cs.agencyId(), cs.artefactId(), cs.version())
        + "."
        + catalog.concept(conceptId).code();
  }

  private void textFormat(DataType type) {
    String textType =
        switch (type) {
          case NUMBER -> "Double";
          case DATE -> "ObservationalTimePeriod";
          case STRING -> "String";
        };
    xml.append("<str:TextFormat textType=\"").append(textType).append("\"/>");
  }

  static String urn(String clazz, String agency, String id, String ver) {
    return "urn:sdmx:org.sdmx.infomodel." + clazz + "=" + agency + ":" + id + "(" + ver + ")";
  }

  static String esc(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
