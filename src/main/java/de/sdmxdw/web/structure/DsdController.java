package de.sdmxdw.web.structure;

import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
import de.sdmxdw.structure.dsd.Dimension;
import de.sdmxdw.web.ConceptLabels;
import java.util.HashMap;
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
@RequestMapping("/structures/dsds")
public class DsdController {

  private final DataStructureDefinitionService dsdService;
  private final ConceptSchemeService conceptSchemeService;
  private final CodelistService codelistService;

  public DsdController(
      DataStructureDefinitionService dsdService,
      ConceptSchemeService conceptSchemeService,
      CodelistService codelistService) {
    this.dsdService = dsdService;
    this.conceptSchemeService = conceptSchemeService;
    this.codelistService = codelistService;
  }

  @GetMapping
  public String list(Model model) {
    model.addAttribute("dsds", dsdService.findAll());
    return "structure/dsd-list";
  }

  @PostMapping
  public String create(
      @RequestParam String agencyId,
      @RequestParam String artefactId,
      @RequestParam String version,
      @RequestParam String name) {
    dsdService.createDsd(agencyId, artefactId, version, name);
    return "redirect:/structures/dsds";
  }

  @GetMapping("/{id}")
  public String detail(@PathVariable Long id, Model model) {
    DataStructureDefinition dsd = dsdService.findById(id);
    Map<Long, String> conceptLabels = ConceptLabels.byConceptId(conceptSchemeService);
    List<ConceptOption> conceptOptions =
        conceptLabels.entrySet().stream()
            .map(e -> new ConceptOption(e.getKey(), e.getValue()))
            .sorted(java.util.Comparator.comparing(ConceptOption::label))
            .toList();
    Map<Long, String> codelistLabels = new HashMap<>();
    List<Codelist> codelists = codelistService.findAll();
    for (Codelist codelist : codelists) {
      codelistLabels.put(codelist.id(), codelist.artefactId() + " - " + codelist.name());
    }

    List<DimensionRow> dimensionRows =
        dsd.dimensions().stream()
            .sorted(java.util.Comparator.comparingInt(Dimension::position))
            .map(
                d ->
                    new DimensionRow(
                        d.position(),
                        conceptLabels.getOrDefault(d.conceptId(), "#" + d.conceptId()),
                        d.codelistId() == null
                            ? "(uncoded)"
                            : codelistLabels.getOrDefault(d.codelistId(), "#" + d.codelistId()),
                        d.dataType().name()))
            .toList();
    List<MeasureRow> measureRows =
        dsd.measures().stream()
            .map(
                m ->
                    new MeasureRow(
                        conceptLabels.getOrDefault(m.conceptId(), "#" + m.conceptId()),
                        m.dataType().name()))
            .toList();

    model.addAttribute("dsd", dsd);
    model.addAttribute("dimensionRows", dimensionRows);
    model.addAttribute("measureRows", measureRows);
    model.addAttribute("conceptOptions", conceptOptions);
    model.addAttribute("codelists", codelists);
    model.addAttribute("dataTypes", DataType.values());
    return "structure/dsd-detail";
  }

  @PostMapping("/{id}/dimensions")
  public String addDimension(
      @PathVariable Long id,
      @RequestParam Long conceptId,
      @RequestParam(required = false) String codelistId,
      @RequestParam DataType dataType) {
    Long codelistIdValue =
        (codelistId == null || codelistId.isBlank()) ? null : Long.valueOf(codelistId);
    dsdService.addDimension(id, conceptId, codelistIdValue, dataType);
    return "redirect:/structures/dsds/" + id;
  }

  @PostMapping("/{id}/measures")
  public String addMeasure(
      @PathVariable Long id, @RequestParam Long conceptId, @RequestParam DataType dataType) {
    dsdService.addMeasure(id, conceptId, dataType);
    return "redirect:/structures/dsds/" + id;
  }

  public record ConceptOption(Long id, String label) {}

  public record DimensionRow(
      int position, String conceptLabel, String codelistLabel, String dataType) {}

  public record MeasureRow(String conceptLabel, String dataType) {}
}
