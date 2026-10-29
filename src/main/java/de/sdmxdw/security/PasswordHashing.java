package de.sdmxdw.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHashing {

  private final PasswordEncoder passwordEncoder;

  public PasswordHashing(PasswordEncoder passwordEncoder) {
    this.passwordEncoder = passwordEncoder;
  }

  public String hash(String rawPassword) {
    return passwordEncoder.encode(rawPassword);
  }

  public boolean matches(String rawPassword, String passwordHash) {
    return passwordEncoder.matches(rawPassword, passwordHash);
  }
}
