package de.sdmxdw.web.microdata;

import de.sdmxdw.microdata.FacetSlot;
import de.sdmxdw.microdata.FieldDataType;
import de.sdmxdw.microdata.FieldDefinition;
import de.sdmxdw.microdata.MicrodataCategorisationService;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.structure.category.Category;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.dataflow.DataflowService;
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
@RequestMapping("/microdata")
public class MicrodataFlowController {

  private final MicrodataFlowService microdataFlowService;
  private final MicrodataRecordService microdataRecordService;
  private final MicrodataCategorisationService microdataCategorisationService;
  private final CodelistService codelistService;
  private final DataflowService dataflowService;
  private final CategorySchemeService categorySchemeService;

  public MicrodataFlowController(
      MicrodataFlowService microdataFlowService,
      MicrodataRecordService microdataRecordService,
      MicrodataCategorisationService microdataCategorisationService,
      CodelistService codelistService,
      DataflowService dataflowService,
      CategorySchemeService categorySchemeService) {
    this.microdataFlowService = microdataFlowService;
    this.microdataRecordService = microdataRecordService;
    this.microdataCategorisationService = microdataCategorisationService;
    this.codelistService = codelistService;
    this.dataflowService = dataflowService;
    this.categorySchemeService = categorySchemeService;
  }

  @GetMapping
  public String list(Model model) {
    model.addAttribute("flows", microdataFlowService.findAll());
    return "microdata/flow-list";
  }

  @GetMapping("/new")
  public String newForm() {
    return "microdata/flow-new";
  }

  @PostMapping
  public String create(
      @RequestParam String agencyId,
      @RequestParam String artefactId,
      @RequestParam String version,
      @RequestParam String name,
      @RequestParam(required = false) String description) {
    MicrodataFlow flow =
        microdataFlowService.createFlow(agencyId, artefactId, version, name, description);
    return "redirect:/microdata/" + flow.id();
  }

  @GetMapping("/{id}")
  public String detail(@PathVariable Long id, Model model) {
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
        microdataCategorisationService
            .findByMicrodataFlowId(id)
            .map(
                c ->
                    categoryOptions.stream()
                        .filter(o -> o.id().equals(c.categoryId()))
                        .findFirst()
                        .map(CategoryOption::label)
                        .orElse("#" + c.categoryId()))
            .orElse("(no category assigned)");

    model.addAttribute("flow", microdataFlowService.findById(id));
    model.addAttribute("fields", microdataFlowService.findFields(id));
    model.addAttribute("fieldDataTypes", FieldDataType.values());
    model.addAttribute("facetSlots", FacetSlot.values());
    model.addAttribute("codelists", codelistService.findAll());
    model.addAttribute("dataflows", dataflowService.findAll());
    model.addAttribute("microdataFlows", microdataFlowService.findAll());
    model.addAttribute("categoryOptions", categoryOptions);
    model.addAttribute("currentCategoryLabel", currentCategoryLabel);
    return "microdata/flow-detail";
  }

  @PostMapping("/{id}/categorisation")
  public String assignCategory(@PathVariable Long id, @RequestParam Long categoryId) {
    microdataCategorisationService.assignFlowToCategory(id, categoryId);
    return "redirect:/microdata/" + id;
  }

  @PostMapping("/{id}/fields")
  public String addField(
      @PathVariable Long id,
      @RequestParam String fieldCode,
      @RequestParam String label,
      @RequestParam FieldDataType dataType,
      @RequestParam(required = false) Long codelistId,
      @RequestParam(defaultValue = "false") boolean required,
      @RequestParam(required = false) FacetSlot facetSlot,
      @RequestParam(required = false) String obligation,
      @RequestParam(required = false) String subtype,
      @RequestParam(required = false) String subtypeRule) {
    FieldDefinition field =
        microdataFlowService.addField(
            id, fieldCode, label, dataType, codelistId, required, facetSlot);
    document(field, obligation, subtype, subtypeRule);
    return "redirect:/microdata/" + id;
  }

  @PostMapping("/{id}/group-fields")
  public String addGroupField(
      @PathVariable Long id,
      @RequestParam String groupCode,
      @RequestParam String fieldCode,
      @RequestParam String label,
      @RequestParam FieldDataType dataType,
      @RequestParam(required = false) Long codelistId,
      @RequestParam(defaultValue = "false") boolean required,
      @RequestParam(required = false) String obligation,
      @RequestParam(required = false) String subtype,
      @RequestParam(required = false) String subtypeRule) {
    FieldDefinition field =
        microdataFlowService.addGroupField(
            id, groupCode, fieldCode, label, dataType, codelistId, required);
    document(field, obligation, subtype, subtypeRule);
    return "redirect:/microdata/" + id;
  }

  @PostMapping("/{id}/reference-fields")
  public String addReferenceField(
      @PathVariable Long id,
      @RequestParam String fieldCode,
      @RequestParam String label,
      @RequestParam Long referencedFlowId,
      @RequestParam(defaultValue = "false") boolean required,
      @RequestParam(required = false) FacetSlot facetSlot,
      @RequestParam(required = false) String obligation,
      @RequestParam(required = false) String subtype,
      @RequestParam(required = false) String subtypeRule) {
    FieldDefinition field =
        microdataFlowService.addReferenceField(
            id, fieldCode, label, referencedFlowId, required, facetSlot);
    document(field, obligation, subtype, subtypeRule);
    return "redirect:/microdata/" + id;
  }

  private void document(
      FieldDefinition field, String obligation, String subtype, String subtypeRule) {
    boolean anyGiven =
        java.util.stream.Stream.of(obligation, subtype, subtypeRule)
            .anyMatch(v -> v != null && !v.isBlank());
    if (anyGiven) {
      microdataFlowService.documentField(field.id(), obligation, subtype, subtypeRule);
    }
  }

  @PostMapping("/{id}/link-dataflow")
  public String linkDataflow(@PathVariable Long id, @RequestParam Long dataflowId) {
    microdataFlowService.linkDataflow(id, dataflowId);
    return "redirect:/microdata/" + id;
  }

  public record CategoryOption(Long id, String label) {}
}
