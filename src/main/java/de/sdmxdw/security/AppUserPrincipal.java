package de.sdmxdw.security;

import de.sdmxdw.user.AppUser;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AppUserPrincipal implements UserDetails {

  private final AppUser appUser;

  public AppUserPrincipal(AppUser appUser) {
    this.appUser = appUser;
  }

  public Long id() {
    return appUser.id();
  }

  public boolean mustChangePassword() {
    return appUser.mustChangePassword();
  }

  public String fullName() {
    return appUser.firstName() + " " + appUser.lastName();
  }

  public String initials() {
    return (firstLetter(appUser.firstName()) + firstLetter(appUser.lastName())).toUpperCase();
  }

  public String role() {
    return appUser.role().name();
  }

  private static String firstLetter(String value) {
    return value == null || value.isBlank() ? "" : value.substring(0, 1);
  }

  @Override
  public String getUsername() {
    return appUser.email();
  }

  @Override
  public String getPassword() {
    return appUser.passwordHash();
  }

  @Override
  public List<GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + appUser.role().name()));
  }
}
