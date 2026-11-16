package de.sdmxdw.web.structure;

import de.sdmxdw.structure.annotation.AnnotationService;
import de.sdmxdw.structure.annotation.AnnotationTarget;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Adds, changes and removes SDMX annotations of structure artefacts (#15); the forms are part of
 * the structure pages (fragment structure/fragments/annotations) and return to them.
 */
@Controller
@RequestMapping("/structures/annotations")
public class AnnotationController {

  private final AnnotationService annotationService;

  public AnnotationController(AnnotationService annotationService) {
    this.annotationService = annotationService;
  }

  @PostMapping
  public String add(
      @RequestParam AnnotationTarget target,
      @RequestParam Long targetId,
      @RequestParam(required = false) String annotationId,
      @RequestParam(required = false) String annotationType,
      @RequestParam(required = false) String title,
      @RequestParam(required = false) String text,
      @RequestParam(required = false) String url,
      @RequestParam String back,
      Authentication auth,
      RedirectAttributes redirect) {
    try {
      annotationService.add(
          target, targetId, annotationId, annotationType, title, text, url, auth.getName());
      redirect.addFlashAttribute("annotationFlash", "Annotation added.");
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("annotationError", e.getMessage());
    }
    return "redirect:" + safe(back);
  }

  @PostMapping("/{id}")
  public String update(
      @PathVariable Long id,
      @RequestParam(required = false) String annotationId,
      @RequestParam(required = false) String annotationType,
      @RequestParam(required = false) String title,
      @RequestParam(required = false) String text,
      @RequestParam(required = false) String url,
      @RequestParam String back,
      Authentication auth,
      RedirectAttributes redirect) {
    try {
      annotationService.update(id, annotationId, annotationType, title, text, url, auth.getName());
      redirect.addFlashAttribute("annotationFlash", "Annotation saved.");
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("annotationError", e.getMessage());
    }
    return "redirect:" + safe(back);
  }

  @PostMapping("/{id}/delete")
  public String delete(
      @PathVariable Long id, @RequestParam String back, RedirectAttributes redirect) {
    annotationService.delete(id);
    redirect.addFlashAttribute("annotationFlash", "Annotation removed.");
    return "redirect:" + safe(back);
  }

  /** Only back to a structure page of this application. */
  static String safe(String back) {
    return back != null && back.matches("/structures/[A-Za-z0-9/_#.-]*")
        ? back
        : "/structures/dataflows";
  }
}
