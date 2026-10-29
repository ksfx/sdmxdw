package de.sdmxdw.web.structure;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The former Datenmanager data page. Its functions (observation table, correction, recording,
 * promotion, audit log) live in the explorer now; old links and bookmarks are redirected there.
 */
@Controller
public class DataflowDataController {

  @GetMapping("/structures/dataflows/{dataflowId}/data")
  public String redirect(@PathVariable Long dataflowId) {
    return "redirect:/explore/dataflows/" + dataflowId + "?view=observations";
  }
}
