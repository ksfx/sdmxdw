package de.sdmxdw.web;

import de.sdmxdw.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

  @GetMapping("/")
  public String home(@AuthenticationPrincipal AppUserPrincipal currentUser, Model model) {
    model.addAttribute("fullName", currentUser.fullName());
    return "home";
  }
}
