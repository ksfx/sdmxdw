package de.sdmxdw.web.explore;

import de.sdmxdw.microdata.MicrodataCategorisationService;
import de.sdmxdw.structure.categorisation.CategorisationService;
import de.sdmxdw.structure.category.Category;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The path of an explorer page: Explore > category scheme > categories > flow (the page adds what
 * follows, e.g. a table or a series). Built from the flow's categorisation.
 */
@Component
public class Breadcrumbs {

  /** One step; {@code href} null = the current page. */
  public record Crumb(String label, String title, String href) {}

  private final CategorySchemeService categorySchemeService;
  private final CategorisationService categorisationService;
  private final MicrodataCategorisationService microdataCategorisationService;

  public Breadcrumbs(
      CategorySchemeService categorySchemeService,
      CategorisationService categorisationService,
      MicrodataCategorisationService microdataCategorisationService) {
    this.categorySchemeService = categorySchemeService;
    this.categorisationService = categorisationService;
    this.microdataCategorisationService = microdataCategorisationService;
  }

  /** Explore time series > scheme > categories; the caller appends the dataflow and below. */
  public List<Crumb> forDataflow(Long dataflowId) {
    List<Crumb> crumbs = new ArrayList<>();
    crumbs.add(new Crumb("Explore time series", null, "/explore"));
    categories(
        categorisationService.findByDataflowId(dataflowId).map(c -> c.categoryId()), crumbs, false);
    return crumbs;
  }

  /** Explore time series > scheme > parent categories > the category itself (current page). */
  public List<Crumb> forCategory(Long categoryId) {
    List<Crumb> crumbs = new ArrayList<>();
    crumbs.add(new Crumb("Explore time series", null, "/explore"));
    categories(Optional.of(categoryId), crumbs, false);
    Crumb last = crumbs.removeLast();
    crumbs.add(new Crumb(last.label(), last.title(), null));
    return crumbs;
  }

  /** Explore microdata > scheme > categories; the caller appends the flow and below. */
  public List<Crumb> forMicrodataFlow(Long flowId) {
    List<Crumb> crumbs = new ArrayList<>();
    crumbs.add(new Crumb("Explore microdata", null, "/explore/microdata"));
    categories(
        microdataCategorisationService.findByMicrodataFlowId(flowId).map(c -> c.categoryId()),
        crumbs,
        true);
    return crumbs;
  }

  /**
   * Scheme and category path; microdata link to the scheme page (categories as anchors there), time
   * series to the explorer and the category page.
   */
  private void categories(Optional<Long> categoryId, List<Crumb> crumbs, boolean microdata) {
    if (categoryId.isEmpty()) {
      return;
    }
    for (CategoryScheme scheme : categorySchemeService.findAll()) {
      Map<Long, Category> byId =
          scheme.categories().stream().collect(Collectors.toMap(Category::id, c -> c));
      Category category = byId.get(categoryId.get());
      if (category == null) {
        continue;
      }
      String schemeHref =
          microdata ? "/explore/microdata/category-schemes/" + scheme.id() : "/explore";
      Function<Long, String> categoryHref =
          microdata ? id -> schemeHref + "#cat-" + id : id -> "/explore/categories/" + id;
      crumbs.add(new Crumb(scheme.name(), scheme.artefactId(), schemeHref));
      List<Category> path = new ArrayList<>();
      for (Category c = category; c != null; c = byId.get(c.parentCategoryId())) {
        path.addFirst(c);
        if (c.parentCategoryId() == null) {
          break;
        }
      }
      path.forEach(c -> crumbs.add(new Crumb(c.name(), c.code(), categoryHref.apply(c.id()))));
      return;
    }
  }
}
