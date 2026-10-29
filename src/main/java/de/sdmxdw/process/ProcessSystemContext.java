package de.sdmxdw.process;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Process runs act as a Datenmanager-level system identity ("process:NAME#RUN") when they load
 * files or run built-in steps - the service rules stay in force, no bypass is needed.
 */
final class ProcessSystemContext {

  private ProcessSystemContext() {}

  static <T> T runAs(String identity, Supplier<T> action) {
    SecurityContext previous = SecurityContextHolder.getContext();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            identity, null, List.of(new SimpleGrantedAuthority("ROLE_DATENMANAGER"))));
    SecurityContextHolder.setContext(context);
    try {
      return action.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
