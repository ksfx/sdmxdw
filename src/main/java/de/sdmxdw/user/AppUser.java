package de.sdmxdw.user;

import java.io.Serializable;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("app_user")
public record AppUser(
    @Id Long id,
    String firstName,
    String lastName,
    String email,
    String passwordHash,
    AppUserRole role,
    boolean mustChangePassword,
    Instant createdAt)
    implements Serializable {

  public static AppUser createNew(
      String firstName, String lastName, String email, String passwordHash, AppUserRole role) {
    return new AppUser(null, firstName, lastName, email, passwordHash, role, true, Instant.now());
  }

  public AppUser withName(String newFirstName, String newLastName) {
    return new AppUser(
        id, newFirstName, newLastName, email, passwordHash, role, mustChangePassword, createdAt);
  }

  public AppUser withPasswordHash(String newPasswordHash) {
    return new AppUser(id, firstName, lastName, email, newPasswordHash, role, false, createdAt);
  }
}
