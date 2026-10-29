package de.sdmxdw.web;

import de.sdmxdw.user.AppUserRole;
import de.sdmxdw.user.AppUserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/users")
public class UserController {

  private final AppUserService appUserService;

  public UserController(AppUserService appUserService) {
    this.appUserService = appUserService;
  }

  @GetMapping
  public String list(Model model) {
    model.addAttribute("users", appUserService.findAll());
    return "users/list";
  }

  @GetMapping("/new")
  public String newForm(Model model) {
    model.addAttribute("roles", AppUserRole.values());
    return "users/new";
  }

  @PostMapping
  public String create(
      @RequestParam String firstName,
      @RequestParam String lastName,
      @RequestParam String email,
      @RequestParam String initialPassword,
      @RequestParam AppUserRole role) {
    appUserService.createUser(firstName, lastName, email, initialPassword, role);
    return "redirect:/users";
  }
}
