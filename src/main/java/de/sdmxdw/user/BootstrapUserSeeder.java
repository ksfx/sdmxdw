package de.sdmxdw.user;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Without this, nobody could log in to create the first Datenmanager account (user creation itself
 * requires an authenticated Datenmanager). Runs with a transient system authentication since {@code
 * createUser} is {@code @PreAuthorize}-protected and there is no logged-in user yet at application
 * startup.
 *
 * <p>The initial password comes from {@code sdmxdw.bootstrap.admin-password} (environment variable
 * SDMXDW_ADMIN_PASSWORD); without it a random password is generated and logged once. Either way it
 * must be changed at the first login.
 */
@Component
public class BootstrapUserSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BootstrapUserSeeder.class);
  private static final String BOOTSTRAP_EMAIL = "admin@sdmxdw.local";

  private final AppUserRepository appUserRepository;
  private final AppUserService appUserService;
  private final String configuredPassword;

  public BootstrapUserSeeder(
      AppUserRepository appUserRepository,
      AppUserService appUserService,
      @Value("${sdmxdw.bootstrap.admin-password:}") String configuredPassword) {
    this.appUserRepository = appUserRepository;
    this.appUserService = appUserService;
    this.configuredPassword = configuredPassword;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (appUserRepository.existsByEmail(BOOTSTRAP_EMAIL)) {
      return;
    }
    boolean generated = configuredPassword == null || configuredPassword.isBlank();
    String password = generated ? randomPassword() : configuredPassword;
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "system-bootstrap",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_DATENMANAGER"))));
    try {
      appUserService.createUser(
          "Admin", "Bootstrap", BOOTSTRAP_EMAIL, password, AppUserRole.DATENMANAGER);
    } finally {
      SecurityContextHolder.clearContext();
    }
    if (generated) {
      log.warn(
          "Created bootstrap Datenmanager account {} with the generated password {} - change it"
              + " at the first login (or set SDMXDW_ADMIN_PASSWORD before the first start)",
          BOOTSTRAP_EMAIL,
          password);
    } else {
      log.info(
          "Created bootstrap Datenmanager account {} (password from configuration)",
          BOOTSTRAP_EMAIL);
    }
  }

  private static String randomPassword() {
    byte[] bytes = new byte[12];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
