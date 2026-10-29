package de.sdmxdw.web.structure;

import de.sdmxdw.structure.categorisation.CategorisationService;
import de.sdmxdw.structure.category.Category;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/structures/dataflows")
public class DataflowController {

  private final DataflowService dataflowService;
  private final DataStructureDefinitionService dsdService;
  private final CategorySchemeService categorySchemeService;
  private final CategorisationService categorisationService;

  public DataflowController(
      DataflowService dataflowService,
      DataStructureDefinitionService dsdService,
      CategorySchemeService categorySchemeService,
      CategorisationService categorisationService) {
    this.dataflowService = dataflowService;
    this.dsdService = dsdService;
    this.categorySchemeService = categorySchemeService;
    this.categorisationService = categorisationService;
  }

  @GetMapping
  public String list(Model model) {
    model.addAttribute("dataflows", dataflowService.findAll());
    return "structure/dataflow-list";
  }

  @GetMapping("/new")
  public String newForm(Model model) {
    model.addAttribute("dsds", dsdService.findAll());
    return "structure/dataflow-new";
  }

  @PostMapping
  public String create(
      @RequestParam String agencyId,
      @RequestParam String artefactId,
      @RequestParam String version,
      @RequestParam String name,
      @RequestParam Long dsdId) {
    dataflowService.createDataflow(agencyId, artefactId, version, name, dsdId);
    return "redirect:/structures/dataflows";
  }

  @GetMapping("/{id}")
  public String detail(@PathVariable Long id, Model model) {
    Dataflow dataflow = dataflowService.findById(id);
    List<CategoryOption> categoryOptions = new ArrayList<>();
    for (CategoryScheme scheme : categorySchemeService.findAll()) {
      for (Category category : scheme.categories()) {
        categoryOptions.add(
            new CategoryOption(
                category.id(),
                scheme.artefactId() + ":" + category.code() + " - " + category.name()));
      }
    }
    String currentCategoryLabel =
        categorisationService
            .findByDataflowId(id)
            .map(
                c ->
                    categoryOptions.stream()
                        .filter(o -> o.id().equals(c.categoryId()))
                        .findFirst()
                        .map(CategoryOption::label)
                        .orElse("#" + c.categoryId()))
            .orElse("(no category assigned)");

    model.addAttribute("dataflow", dataflow);
    model.addAttribute("categoryOptions", categoryOptions);
    model.addAttribute("currentCategoryLabel", currentCategoryLabel);
    return "structure/dataflow-detail";
  }

  @PostMapping("/{id}/categorisation")
  public String assignCategory(@PathVariable Long id, @RequestParam Long categoryId) {
    categorisationService.assignDataflowToCategory(id, categoryId);
    return "redirect:/structures/dataflows/" + id;
  }

  public record CategoryOption(Long id, String label) {}
}
