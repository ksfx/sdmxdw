package de.sdmxdw.web.microdata;

import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The former "Manage microdata" record pages. Browsing, correction, promotion and the activity log
 * live in the explorer now; old links and bookmarks are redirected there.
 */
@Controller
@RequestMapping("/microdata/{flowId}/records")
public class MicrodataRecordController {

  private final MicrodataFlowService microdataFlowService;

  public MicrodataRecordController(MicrodataFlowService microdataFlowService) {
    this.microdataFlowService = microdataFlowService;
  }

  @GetMapping
  public String list(@PathVariable Long flowId, @RequestParam(required = false) String stage) {
    return "redirect:/explore/microdata/" + flowId + ("ALPHA".equals(stage) ? "?stage=ALPHA" : "");
  }

  @GetMapping("/{recordId}")
  public String detail(@PathVariable Long flowId, @PathVariable Long recordId) {
    return "redirect:/explore/microdata/" + flowId + "/records/" + recordId;
  }

  /** The CSV export moved to the read API (SDMX-CSV, also with filters and Parquet). */
  @GetMapping("/export.csv")
  public String exportCsv(@PathVariable Long flowId, @RequestParam(required = false) String stage) {
    MicrodataFlow flow = microdataFlowService.findById(flowId);
    return "redirect:"
        + UriComponentsBuilder.fromPath("/rest/microdata/{ref}")
            .queryParam("stage", "ALPHA".equals(stage) ? "ALPHA" : null)
            .buildAndExpand(flow.agencyId() + "," + flow.artefactId() + "," + flow.version())
            .encode()
            .toUriString();
  }
}
