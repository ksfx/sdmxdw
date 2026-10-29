package de.sdmxdw.web.rest;

import de.sdmxdw.structure.categorisation.Categorisation;
import de.sdmxdw.structure.category.Category;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.conceptscheme.Concept;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.Dimension;
import de.sdmxdw.structure.dsd.Measure;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory snapshot of all SDMX structural metadata, used to answer one structure query. The
 * structure model is small (tens of artefacts), so loading it completely per request is cheaper and
 * simpler than one query per reference hop.
 */
final class StructureCatalog {

  /** SDMX-REST structure types this instance can serve. */
  enum Type {
    dataflow,
    datastructure,
    codelist,
    conceptscheme,
    categoryscheme,
    categorisation
  }

  record Node(Type type, Long id) {}

  final Map<Long, Dataflow> dataflows;
  final Map<Long, DataStructureDefinition> dsds;
  final Map<Long, Codelist> codelists;
  final Map<Long, ConceptScheme> conceptSchemes;
  final Map<Long, CategoryScheme> categorySchemes;
  final Map<Long, Categorisation> categorisations;
  private final Map<Long, ConceptScheme> schemeByConceptId = new HashMap<>();
  private final Map<Long, Concept> conceptById = new HashMap<>();
  private final Map<Long, CategoryScheme> schemeByCategoryId = new HashMap<>();

  StructureCatalog(
      Collection<Dataflow> dataflows,
      Collection<DataStructureDefinition> dsds,
      Collection<Codelist> codelists,
      Collection<ConceptScheme> conceptSchemes,
      Collection<CategoryScheme> categorySchemes,
      Collection<Categorisation> categorisations) {
    this.dataflows = byId(dataflows, Dataflow::id);
    this.dsds = byId(dsds, DataStructureDefinition::id);
    this.codelists = byId(codelists, Codelist::id);
    this.conceptSchemes = byId(conceptSchemes, ConceptScheme::id);
    this.categorySchemes = byId(categorySchemes, CategoryScheme::id);
    this.categorisations = byId(categorisations, Categorisation::id);
    conceptSchemes.forEach(
        cs ->
            cs.concepts()
                .forEach(
                    c -> {
                      schemeByConceptId.put(c.id(), cs);
                      conceptById.put(c.id(), c);
                    }));
    categorySchemes.forEach(cs -> cs.categories().forEach(c -> schemeByCategoryId.put(c.id(), cs)));
  }

  private static <T> Map<Long, T> byId(Collection<T> items, Function<T, Long> id) {
    return items.stream()
        .collect(Collectors.toMap(id, t -> t, (a, b) -> a, java.util.LinkedHashMap::new));
  }

  Concept concept(Long conceptId) {
    return conceptById.get(conceptId);
  }

  ConceptScheme conceptSchemeOf(Long conceptId) {
    return schemeByConceptId.get(conceptId);
  }

  CategoryScheme categorySchemeOf(Long categoryId) {
    return schemeByCategoryId.get(categoryId);
  }

  /** Category ids from the root down to the given category (SDMX nested category id path). */
  List<String> categoryPath(Long categoryId) {
    CategoryScheme scheme = schemeByCategoryId.get(categoryId);
    Map<Long, Category> byId =
        scheme.categories().stream().collect(Collectors.toMap(Category::id, c -> c));
    List<String> path = new ArrayList<>();
    for (Category c = byId.get(categoryId); c != null; c = byId.get(c.parentCategoryId())) {
      path.addFirst(c.code());
    }
    return path;
  }

  /** Identification of any node: agency, id, version. */
  String[] triple(Node node) {
    return switch (node.type()) {
      case dataflow -> {
        Dataflow d = dataflows.get(node.id());
        yield new String[] {d.agencyId(), d.artefactId(), d.version()};
      }
      case datastructure -> {
        DataStructureDefinition d = dsds.get(node.id());
        yield new String[] {d.agencyId(), d.artefactId(), d.version()};
      }
      case codelist -> {
        Codelist c = codelists.get(node.id());
        yield new String[] {c.agencyId(), c.artefactId(), c.version()};
      }
      case conceptscheme -> {
        ConceptScheme c = conceptSchemes.get(node.id());
        yield new String[] {c.agencyId(), c.artefactId(), c.version()};
      }
      case categoryscheme -> {
        CategoryScheme c = categorySchemes.get(node.id());
        yield new String[] {c.agencyId(), c.artefactId(), c.version()};
      }
      case categorisation -> {
        Dataflow d = dataflows.get(categorisations.get(node.id()).dataflowId());
        yield new String[] {d.agencyId(), categorisationId(categorisations.get(node.id())), "1.0"};
      }
    };
  }

  /** Categorisations have no own identifier in SDMxDW; derive a stable one. */
  String categorisationId(Categorisation c) {
    Dataflow d = dataflows.get(c.dataflowId());
    return "CAT_" + d.artefactId() + "_" + String.join("_", categoryPath(c.categoryId()));
  }

  List<Node> all(Type type) {
    Collection<Long> ids =
        switch (type) {
          case dataflow -> dataflows.keySet();
          case datastructure -> dsds.keySet();
          case codelist -> codelists.keySet();
          case conceptscheme -> conceptSchemes.keySet();
          case categoryscheme -> categorySchemes.keySet();
          case categorisation -> categorisations.keySet();
        };
    return ids.stream().map(id -> new Node(type, id)).toList();
  }

  /** Artefacts the node references (SDMX "children"). */
  Set<Node> children(Node node) {
    Set<Node> result = new LinkedHashSet<>();
    switch (node.type()) {
      case dataflow -> {
        Dataflow d = dataflows.get(node.id());
        if (dsds.containsKey(d.dsdId())) {
          result.add(new Node(Type.datastructure, d.dsdId()));
        }
      }
      case datastructure -> {
        DataStructureDefinition dsd = dsds.get(node.id());
        for (Dimension dim : dsd.dimensions()) {
          if (dim.codelistId() != null) {
            result.add(new Node(Type.codelist, dim.codelistId()));
          }
          addConceptScheme(result, dim.conceptId());
        }
        for (Measure m : dsd.measures()) {
          addConceptScheme(result, m.conceptId());
        }
      }
      case categorisation -> {
        Categorisation c = categorisations.get(node.id());
        result.add(new Node(Type.dataflow, c.dataflowId()));
        CategoryScheme scheme = schemeByCategoryId.get(c.categoryId());
        if (scheme != null) {
          result.add(new Node(Type.categoryscheme, scheme.id()));
        }
      }
      default -> {}
    }
    return result;
  }

  private void addConceptScheme(Set<Node> result, Long conceptId) {
    ConceptScheme scheme = schemeByConceptId.get(conceptId);
    if (scheme != null) {
      result.add(new Node(Type.conceptscheme, scheme.id()));
    }
  }

  /** Artefacts that reference the node (SDMX "parents"). */
  Set<Node> parents(Node node) {
    Set<Node> result = new LinkedHashSet<>();
    for (Type type : List.of(Type.dataflow, Type.datastructure, Type.categorisation)) {
      for (Node candidate : all(type)) {
        if (children(candidate).contains(node)) {
          result.add(candidate);
        }
      }
    }
    return result;
  }

  Set<Node> descendants(Node node) {
    Set<Node> result = new LinkedHashSet<>();
    List<Node> queue = new ArrayList<>(children(node));
    while (!queue.isEmpty()) {
      Node next = queue.removeFirst();
      if (result.add(next)) {
        queue.addAll(children(next));
      }
    }
    return result;
  }

  /**
   * Expands the matched artefacts by the SDMX-REST {@code references} parameter (none, parents,
   * parentsandsiblings, children, descendants, all, or a structure type name).
   */
  Set<Node> withReferences(List<Node> matched, String references) {
    Set<Node> result = new LinkedHashSet<>(matched);
    String ref = references == null ? "none" : references.toLowerCase();
    for (Node node : matched) {
      switch (ref) {
        case "none" -> {}
        case "parents" -> result.addAll(parents(node));
        case "parentsandsiblings" -> addParentsAndSiblings(result, node);
        case "children" -> result.addAll(children(node));
        case "descendants" -> result.addAll(descendants(node));
        case "all" -> {
          addParentsAndSiblings(result, node);
          result.addAll(descendants(node));
        }
        default -> {
          Type type = parseType(ref);
          Set<Node> reachable = new LinkedHashSet<>(descendants(node));
          addParentsAndSiblings(reachable, node);
          reachable.stream().filter(n -> n.type() == type).forEach(result::add);
        }
      }
    }
    return result;
  }

  private void addParentsAndSiblings(Set<Node> result, Node node) {
    for (Node parent : parents(node)) {
      result.add(parent);
      result.addAll(children(parent));
    }
  }

  static Type parseType(String name) {
    for (Type t : Type.values()) {
      if (t.name().equalsIgnoreCase(name)) {
        return t;
      }
    }
    throw new IllegalArgumentException("Unsupported structure type or references value: " + name);
  }

  static boolean sameIds(String[] a, String[] b) {
    return Objects.equals(a[0], b[0]) && Objects.equals(a[1], b[1]);
  }
}
