package de.sdmxdw.web.structure;

import de.sdmxdw.structure.codelist.Code;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import java.util.Comparator;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/structures/codelists")
public class CodelistController {

  private final CodelistService codelistService;

  public CodelistController(CodelistService codelistService) {
    this.codelistService = codelistService;
  }

  @GetMapping
  public String list(Model model) {
    model.addAttribute("codelists", codelistService.findAll());
    return "structure/codelist-list";
  }

  @PostMapping
  public String create(
      @RequestParam String agencyId,
      @RequestParam String artefactId,
      @RequestParam String version,
      @RequestParam String name) {
    codelistService.createCodelist(agencyId, artefactId, version, name);
    return "redirect:/structures/codelists";
  }

  @GetMapping("/{id}")
  public String detail(@PathVariable Long id, Model model) {
    Codelist codelist = codelistService.findById(id);
    model.addAttribute("codelist", codelist);
    model.addAttribute(
        "codes", codelist.codes().stream().sorted(Comparator.comparing(Code::code)).toList());
    return "structure/codelist-detail";
  }

  @PostMapping("/{id}/codes")
  public String addCode(
      @PathVariable Long id,
      @RequestParam String code,
      @RequestParam String name,
      @RequestParam(required = false) String description) {
    codelistService.addCode(id, code, name, description);
    return "redirect:/structures/codelists/" + id;
  }
}
