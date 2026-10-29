package de.sdmxdw.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Role checks for showing or hiding UI functions; the services enforce the actual rules. */
public final class Roles {

  private Roles() {}

  public static boolean isDatenmanager() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null
        && auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_DATENMANAGER"));
  }
}
