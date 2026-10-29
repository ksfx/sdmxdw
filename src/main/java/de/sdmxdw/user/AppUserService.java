package de.sdmxdw.user;

import de.sdmxdw.security.PasswordHashing;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class AppUserService {

  private final AppUserRepository appUserRepository;
  private final PasswordHashing passwordHashing;

  public AppUserService(AppUserRepository appUserRepository, PasswordHashing passwordHashing) {
    this.appUserRepository = appUserRepository;
    this.passwordHashing = passwordHashing;
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<AppUser> findAll() {
    return appUserRepository.findAll();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public AppUser createUser(
      String firstName, String lastName, String email, String initialPassword, AppUserRole role) {
    if (appUserRepository.existsByEmail(email)) {
      throw new IllegalArgumentException("Email already registered: " + email);
    }
    AppUser newUser =
        AppUser.createNew(firstName, lastName, email, passwordHashing.hash(initialPassword), role);
    return appUserRepository.save(newUser);
  }

  public AppUser findById(Long userId) {
    return appUserRepository
        .findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user id: " + userId));
  }

  public AppUser updateName(Long userId, String firstName, String lastName) {
    if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) {
      throw new IllegalArgumentException("First and last name must not be empty");
    }
    return appUserRepository.save(findById(userId).withName(firstName.trim(), lastName.trim()));
  }

  /**
   * Self-service variant for the profile page: unlike the forced first-login change, the user is
   * already fully logged in here, so the current password is re-checked to keep a hijacked session
   * from silently taking over the account.
   */
  public void changePassword(Long userId, String currentPassword, String newPassword) {
    if (!passwordHashing.matches(currentPassword, findById(userId).passwordHash())) {
      throw new IllegalArgumentException("Current password is incorrect");
    }
    changePassword(userId, newPassword);
  }

  public void changePassword(Long userId, String newPassword) {
    AppUser appUser =
        appUserRepository
            .findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown user id: " + userId));
    appUserRepository.save(appUser.withPasswordHash(passwordHashing.hash(newPassword)));
  }
}
