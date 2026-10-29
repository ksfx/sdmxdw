package de.sdmxdw.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Users must change their initial, Datenmanager-assigned password before doing anything else -
 * enforced here rather than per-controller since it applies site-wide.
 */
public class ForcePasswordChangeFilter extends OncePerRequestFilter {

  private static final String CHANGE_PASSWORD_PATH = "/account/change-password";
  private static final Set<String> ALWAYS_ALLOWED =
      Set.of(CHANGE_PASSWORD_PATH, "/login", "/logout");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof AppUserPrincipal principal
        && principal.mustChangePassword()
        && !ALWAYS_ALLOWED.contains(request.getRequestURI())) {
      response.sendRedirect(request.getContextPath() + CHANGE_PASSWORD_PATH);
      return;
    }
    filterChain.doFilter(request, response);
  }
}
