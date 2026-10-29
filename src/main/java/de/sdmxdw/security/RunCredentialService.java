package de.sdmxdw.security;

import de.sdmxdw.user.AppUser;
import de.sdmxdw.user.AppUserRole;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Short-lived read credentials for process runs ({@code sdwrun_...}). The runner issues one per run
 * and passes it to every step as {@code SDMXDW_API_TOKEN}; the SDKs use it for the read API (/rest,
 * GET only - writing happens through the file handoff). Kept in memory only and revoked when the
 * run ends.
 */
@Service
public class RunCredentialService {

  public static final String PREFIX = "sdwrun_";

  private final Map<String, RunCredential> credentials = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  public String issue(Long runId, String processName) {
    byte[] bytes = new byte[30];
    random.nextBytes(bytes);
    String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    credentials.put(token, new RunCredential(runId, processName));
    return token;
  }

  public void revoke(String token) {
    if (token != null) {
      credentials.remove(token);
    }
  }

  /** A run reads like a Datenmanager (Alpha included) - processes are maintained by them. */
  public Optional<AppUserPrincipal> authenticate(String token) {
    RunCredential credential = token == null ? null : credentials.get(token);
    if (credential == null) {
      return Optional.empty();
    }
    return Optional.of(
        new AppUserPrincipal(
            new AppUser(
                null,
                "Process",
                credential.processName(),
                "process:" + credential.processName() + "#" + credential.runId(),
                "",
                AppUserRole.DATENMANAGER,
                false,
                Instant.now())));
  }

  record RunCredential(Long runId, String processName) {}
}
