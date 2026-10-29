package de.sdmxdw.security;

import de.sdmxdw.user.AppUser;
import de.sdmxdw.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal API tokens ("sdw_..."). Tokens are random 240-bit values, so a fast unsalted SHA-256 is
 * enough for storage (no dictionary to attack) and allows lookup by hash on every request.
 */
@Service
public class ApiTokenService {

  public static final String PREFIX = "sdw_";

  // last_used_at is informational; avoid one UPDATE per request for busy scripts
  private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

  private final ApiTokenRepository apiTokenRepository;
  private final AppUserRepository appUserRepository;
  private final SecureRandom random = new SecureRandom();

  public ApiTokenService(
      ApiTokenRepository apiTokenRepository, AppUserRepository appUserRepository) {
    this.apiTokenRepository = apiTokenRepository;
    this.appUserRepository = appUserRepository;
  }

  /**
   * @param validDays null or <= 0 = no expiry
   * @return the plain token - only available here, it is not stored
   */
  @Transactional
  public CreatedToken create(Long userId, String name, Integer validDays) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Token name is required.");
    }
    byte[] bytes = new byte[30];
    random.nextBytes(bytes);
    String plain = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt =
        validDays == null || validDays <= 0 ? null : now.plus(Duration.ofDays(validDays));
    ApiToken saved =
        apiTokenRepository.save(
            new ApiToken(
                null,
                userId,
                name.strip(),
                hash(plain),
                plain.substring(0, PREFIX.length() + 4) + "…",
                now,
                expiresAt,
                null,
                null));
    return new CreatedToken(saved, plain);
  }

  public List<ApiToken> findByUser(Long userId) {
    return apiTokenRepository.findByUserId(userId);
  }

  @Transactional
  public boolean revoke(Long userId, Long tokenId) {
    return apiTokenRepository.revoke(tokenId, userId, Instant.now()) > 0;
  }

  /** Resolves a presented token to its (current) owner; empty if unknown, revoked or expired. */
  @Transactional
  public Optional<AppUser> authenticate(String plainToken) {
    if (plainToken == null || !plainToken.startsWith(PREFIX)) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    Optional<ApiToken> token =
        apiTokenRepository.findByTokenHash(hash(plainToken)).filter(t -> t.isActive(now));
    token
        .filter(t -> t.lastUsedAt() == null || t.lastUsedAt().plus(TOUCH_INTERVAL).isBefore(now))
        .ifPresent(t -> apiTokenRepository.touch(t.id(), now));
    return token.flatMap(t -> appUserRepository.findById(t.userId()));
  }

  static String hash(String plainToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(plainToken.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public record CreatedToken(ApiToken token, String plainToken) {}
}
