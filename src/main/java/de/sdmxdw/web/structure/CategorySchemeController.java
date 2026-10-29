package de.sdmxdw.web.structure;

import de.sdmxdw.structure.category.Category;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import de.sdmxdw.web.MarkdownRenderer;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/structures/category-schemes")
public class CategorySchemeController {

  private final CategorySchemeService categorySchemeService;
  private final MarkdownRenderer markdownRenderer;

  public CategorySchemeController(
      CategorySchemeService categorySchemeService, MarkdownRenderer markdownRenderer) {
    this.categorySchemeService = categorySchemeService;
    this.markdownRenderer = markdownRenderer;
  }

  @GetMapping
  public String list(Model model) {
    model.addAttribute("schemes", categorySchemeService.findAll());
    return "structure/category-scheme-list";
  }

  @PostMapping
  public String create(
      @RequestParam String agencyId,
      @RequestParam String artefactId,
      @RequestParam String version,
      @RequestParam String name) {
    categorySchemeService.createScheme(agencyId, artefactId, version, name);
    return "redirect:/structures/category-schemes";
  }

  @GetMapping("/{id}")
  public String detail(@PathVariable Long id, Model model) {
    CategoryScheme scheme = categorySchemeService.findById(id);
    Map<Long, String> codeById =
        scheme.categories().stream()
            .collect(java.util.stream.Collectors.toMap(Category::id, Category::code));
    List<CategoryRow> rows =
        scheme.categories().stream()
            .map(
                c ->
                    new CategoryRow(
                        c.code(),
                        c.name(),
                        c.parentCategoryId() == null ? "" : codeById.get(c.parentCategoryId())))
            .sorted((a, b) -> a.code().compareTo(b.code()))
            .toList();
    model.addAttribute("scheme", scheme);
    model.addAttribute("rows", rows);
    model.addAttribute("descriptionHtml", markdownRenderer.render(scheme.description()));
    return "structure/category-scheme-detail";
  }

  @PostMapping("/{id}/description")
  public String updateDescription(
      @PathVariable Long id, @RequestParam(required = false) String description) {
    categorySchemeService.updateDescription(id, description);
    return "redirect:/structures/category-schemes/" + id;
  }

  public record CategoryRow(String code, String name, String parentCode) {}

  @PostMapping("/{id}/categories")
  public String addCategory(
      @PathVariable Long id,
      @RequestParam(required = false) String parentCategoryId,
      @RequestParam String code,
      @RequestParam String name) {
    Long parentId =
        (parentCategoryId == null || parentCategoryId.isBlank())
            ? null
            : Long.valueOf(parentCategoryId);
    categorySchemeService.addCategory(id, parentId, code, name);
    return "redirect:/structures/category-schemes/" + id;
  }
}
