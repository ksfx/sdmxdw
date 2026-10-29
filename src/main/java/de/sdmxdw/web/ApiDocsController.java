package de.sdmxdw.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Human-readable documentation of the read API with Python and R examples. */
@Controller
public class ApiDocsController {

  @GetMapping("/api-docs")
  public String docs(HttpServletRequest request, Model model) {
    model.addAttribute(
        "baseUrl",
        ServletUriComponentsBuilder.fromContextPath(request).path("/rest").toUriString());
    return "api/docs";
  }
}
