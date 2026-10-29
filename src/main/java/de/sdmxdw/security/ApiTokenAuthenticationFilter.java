package de.sdmxdw.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates read-API requests with a personal API token, sent either as {@code Authorization:
 * Bearer sdw_...} or as HTTP Basic password (any user name) - the latter for SDMX clients that only
 * support basic auth, e.g. URLs like {@code https://user:sdw_...@host/rest} in R. The context is
 * not stored in a session: every script request carries its token.
 */
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

  private final ApiTokenService apiTokenService;
  private final RunCredentialService runCredentialService;

  public ApiTokenAuthenticationFilter(
      ApiTokenService apiTokenService, RunCredentialService runCredentialService) {
    this.apiTokenService = apiTokenService;
    this.runCredentialService = runCredentialService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
    if (token != null) {
      Optional<AppUserPrincipal> resolved =
          token.startsWith(RunCredentialService.PREFIX)
              ? runCredentialService.authenticate(token)
              : apiTokenService.authenticate(token).map(AppUserPrincipal::new);
      if (resolved.isEmpty()) {
        unauthorized(response, "Invalid, revoked or expired API token");
        return;
      }
      AppUserPrincipal principal = resolved.get();
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(
              principal, null, principal.getAuthorities()));
      SecurityContextHolder.setContext(context);
    }
    chain.doFilter(request, response);
  }

  /**
   * Writes the 401 directly: sendError() would dispatch to /error, which the browser filter chain
   * answers with a redirect to the login page.
   */
  static void unauthorized(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"sdmxdw\"");
    response.setContentType("text/plain;charset=UTF-8");
    response.getWriter().write(message + "\n");
  }

  static String extractToken(String header) {
    if (header == null) {
      return null;
    }
    if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return header.substring(7).trim();
    }
    if (header.regionMatches(true, 0, "Basic ", 0, 6)) {
      try {
        String decoded =
            new String(
                Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        String password = colon < 0 ? decoded : decoded.substring(colon + 1);
        return password.startsWith(ApiTokenService.PREFIX)
                || password.startsWith(RunCredentialService.PREFIX)
            ? password
            : null;
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    return null;
  }
}
