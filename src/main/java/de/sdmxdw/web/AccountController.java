package de.sdmxdw.web;

import de.sdmxdw.security.ApiTokenService;
import de.sdmxdw.security.AppUserDetailsService;
import de.sdmxdw.security.AppUserPrincipal;
import de.sdmxdw.user.AppUser;
import de.sdmxdw.user.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/account")
public class AccountController {

  private final AppUserService appUserService;
  private final AppUserDetailsService appUserDetailsService;
  private final ApiTokenService apiTokenService;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  public AccountController(
      AppUserService appUserService,
      AppUserDetailsService appUserDetailsService,
      ApiTokenService apiTokenService) {
    this.appUserService = appUserService;
    this.apiTokenService = apiTokenService;
    this.appUserDetailsService = appUserDetailsService;
  }

  @GetMapping("/change-password")
  public String changePasswordForm() {
    return "account/change-password";
  }

  @PostMapping("/change-password")
  public String changePassword(
      @RequestParam String newPassword,
      @RequestParam String confirmPassword,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (!newPassword.equals(confirmPassword)) {
      model.addAttribute("error", "Passwords do not match.");
      return "account/change-password";
    }
    AppUserPrincipal principal = currentPrincipal();
    appUserService.changePassword(principal.id(), newPassword);
    refreshPrincipal(principal, request, response);
    return "redirect:/";
  }

  @GetMapping("/profile")
  public String profile(Model model) {
    AppUser user = appUserService.findById(currentPrincipal().id());
    model.addAttribute("user", user);
    model.addAttribute("apiTokens", apiTokenService.findByUser(user.id()));
    model.addAttribute("now", Instant.now());
    return "account/profile";
  }

  @PostMapping("/profile/tokens")
  public String createToken(
      @RequestParam String name,
      @RequestParam(required = false) Integer validDays,
      RedirectAttributes redirectAttributes) {
    try {
      var created = apiTokenService.create(currentPrincipal().id(), name, validDays);
      redirectAttributes.addFlashAttribute("newToken", created.plainToken());
      redirectAttributes.addFlashAttribute("newTokenName", created.token().name());
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("tokenError", e.getMessage());
    }
    return "redirect:/account/profile#api-tokens";
  }

  @PostMapping("/profile/tokens/{tokenId}/revoke")
  public String revokeToken(@PathVariable Long tokenId) {
    apiTokenService.revoke(currentPrincipal().id(), tokenId);
    return "redirect:/account/profile#api-tokens";
  }

  @PostMapping("/profile/name")
  public String updateName(
      @RequestParam String firstName,
      @RequestParam String lastName,
      RedirectAttributes redirectAttributes,
      HttpServletRequest request,
      HttpServletResponse response) {
    AppUserPrincipal principal = currentPrincipal();
    try {
      appUserService.updateName(principal.id(), firstName, lastName);
      refreshPrincipal(principal, request, response);
      redirectAttributes.addFlashAttribute("nameSuccess", "Name updated.");
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("nameError", e.getMessage());
    }
    return "redirect:/account/profile";
  }

  @PostMapping("/profile/password")
  public String updatePassword(
      @RequestParam String currentPassword,
      @RequestParam String newPassword,
      @RequestParam String confirmPassword,
      RedirectAttributes redirectAttributes) {
    if (!newPassword.equals(confirmPassword)) {
      redirectAttributes.addFlashAttribute("passwordError", "Passwords do not match.");
      return "redirect:/account/profile";
    }
    try {
      appUserService.changePassword(currentPrincipal().id(), currentPassword, newPassword);
      redirectAttributes.addFlashAttribute("passwordSuccess", "Password changed.");
    } catch (IllegalArgumentException e) {
      redirectAttributes.addFlashAttribute("passwordError", e.getMessage());
    }
    return "redirect:/account/profile";
  }

  private AppUserPrincipal currentPrincipal() {
    return (AppUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  /** Reloads the principal so changes (name, must-change flag) show up without re-login. */
  private void refreshPrincipal(
      AppUserPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
    AppUserPrincipal refreshed =
        (AppUserPrincipal) appUserDetailsService.loadUserByUsername(principal.getUsername());
    SecurityContext newContext = SecurityContextHolder.createEmptyContext();
    newContext.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            refreshed, refreshed.getPassword(), refreshed.getAuthorities()));
    SecurityContextHolder.setContext(newContext);
    securityContextRepository.saveContext(newContext, request, response);
  }
}
