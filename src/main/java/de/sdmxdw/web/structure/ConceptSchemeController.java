package de.sdmxdw.web.structure;

import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/structures/concept-schemes")
public class ConceptSchemeController {

  private final ConceptSchemeService conceptSchemeService;

  public ConceptSchemeController(ConceptSchemeService conceptSchemeService) {
    this.conceptSchemeService = conceptSchemeService;
  }

  @GetMapping
  public String list(Model model) {
    model.addAttribute("schemes", conceptSchemeService.findAll());
    return "structure/concept-scheme-list";
  }

  @PostMapping
  public String create(
      @RequestParam String agencyId,
      @RequestParam String artefactId,
      @RequestParam String version,
      @RequestParam String name) {
    conceptSchemeService.createScheme(agencyId, artefactId, version, name);
    return "redirect:/structures/concept-schemes";
  }

  @GetMapping("/{id}")
  public String detail(@PathVariable Long id, Model model) {
    model.addAttribute("scheme", conceptSchemeService.findById(id));
    return "structure/concept-scheme-detail";
  }

  @PostMapping("/{id}/concepts")
  public String addConcept(
      @PathVariable Long id, @RequestParam String code, @RequestParam String name) {
    conceptSchemeService.addConcept(id, code, name);
    return "redirect:/structures/concept-schemes/" + id;
  }
}
