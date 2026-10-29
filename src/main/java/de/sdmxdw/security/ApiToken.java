package de.sdmxdw.security;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("api_token")
public record ApiToken(
    @Id Long id,
    Long userId,
    String name,
    String tokenHash,
    String tokenHint,
    Instant createdAt,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant revokedAt) {

  public boolean isActive(Instant now) {
    return revokedAt == null && (expiresAt == null || now.isBefore(expiresAt));
  }
}
