package de.sdmxdw.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface ApiTokenRepository extends ListCrudRepository<ApiToken, Long> {

  Optional<ApiToken> findByTokenHash(String tokenHash);

  @Query("SELECT * FROM api_token WHERE user_id = :userId ORDER BY created_at DESC")
  List<ApiToken> findByUserId(@Param("userId") Long userId);

  @Modifying
  @Query("UPDATE api_token SET last_used_at = :now WHERE id = :id")
  void touch(@Param("id") Long id, @Param("now") Instant now);

  @Modifying
  @Query(
      "UPDATE api_token SET revoked_at = :now WHERE id = :id AND user_id = :userId"
          + " AND revoked_at IS NULL")
  int revoke(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);
}
