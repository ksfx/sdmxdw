package de.sdmxdw.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.security.PasswordHashing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

class AppUserServiceTest extends AbstractIntegrationTest {

  @Autowired private AppUserService appUserService;

  @Autowired private AppUserRepository appUserRepository;

  @Autowired private PasswordHashing passwordHashing;

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void createsUserWithHashedPasswordAndMustChangePasswordFlag() {
    AppUser created =
        appUserService.createUser(
            "Jane", "Doe", "jane.doe@example.com", "initialPw123", AppUserRole.DATENMANAGER);

    assertThat(created.id()).isNotNull();
    assertThat(created.passwordHash()).isNotEqualTo("initialPw123");
    assertThat(passwordHashing.matches("initialPw123", created.passwordHash())).isTrue();
    assertThat(created.mustChangePassword()).isTrue();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void rejectsDuplicateEmail() {
    appUserService.createUser("Jane", "Doe", "dup@example.com", "pw1", AppUserRole.OEKONOM);

    assertThatThrownBy(
            () ->
                appUserService.createUser(
                    "Other", "Name", "dup@example.com", "pw2", AppUserRole.OEKONOM))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void changePasswordClearsMustChangeFlagAndUpdatesHash() {
    AppUser created =
        appUserService.createUser(
            "Jane", "Doe", "change@example.com", "oldPw", AppUserRole.OEKONOM);

    appUserService.changePassword(created.id(), "newPw456");

    AppUser reloaded = appUserRepository.findById(created.id()).orElseThrow();
    assertThat(reloaded.mustChangePassword()).isFalse();
    assertThat(passwordHashing.matches("newPw456", reloaded.passwordHash())).isTrue();
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void updateNameChangesOnlyTheName() {
    AppUser created =
        appUserService.createUser("Jane", "Doe", "rename@example.com", "pw", AppUserRole.OEKONOM);

    appUserService.updateName(created.id(), " Janet ", "Smith");

    AppUser reloaded = appUserRepository.findById(created.id()).orElseThrow();
    assertThat(reloaded.firstName()).isEqualTo("Janet");
    assertThat(reloaded.lastName()).isEqualTo("Smith");
    assertThat(reloaded.email()).isEqualTo("rename@example.com");
    assertThat(reloaded.mustChangePassword()).isTrue();
    assertThatThrownBy(() -> appUserService.updateName(created.id(), " ", "Smith"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @WithMockUser(roles = "DATENMANAGER")
  void selfServicePasswordChangeRequiresCurrentPassword() {
    AppUser created =
        appUserService.createUser("Jane", "Doe", "self@example.com", "oldPw", AppUserRole.OEKONOM);

    assertThatThrownBy(() -> appUserService.changePassword(created.id(), "wrong", "newPw"))
        .isInstanceOf(IllegalArgumentException.class);

    appUserService.changePassword(created.id(), "oldPw", "newPw");
    AppUser reloaded = appUserRepository.findById(created.id()).orElseThrow();
    assertThat(passwordHashing.matches("newPw", reloaded.passwordHash())).isTrue();
  }

  @Test
  @WithMockUser(roles = "OEKONOM")
  void oekonomCannotManageUsers() {
    assertThatThrownBy(
            () -> appUserService.createUser("X", "Y", "oek@example.com", "pw", AppUserRole.OEKONOM))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> appUserService.findAll()).isInstanceOf(AccessDeniedException.class);
  }
}
