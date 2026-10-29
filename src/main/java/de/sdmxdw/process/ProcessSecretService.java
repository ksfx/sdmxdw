package de.sdmxdw.process;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Credentials for external sources (APIs, SFTP, ...) used by processes. Values are encrypted with
 * AES-GCM; the key lives in a file next to the application data (created on first use, readable
 * only by the service user) and never in the database or the Git repository. Values can be set and
 * deleted but never read back through the UI - only the runner resolves them for a step.
 */
@Service
public class ProcessSecretService {

  private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
  private static final int IV_BYTES = 12;

  private final ProcessSecretRepository repository;
  private final Path keyFile;
  private final SecureRandom random = new SecureRandom();
  private volatile SecretKeySpec key;

  public ProcessSecretService(
      ProcessSecretRepository repository,
      @Value("${sdmxdw.processes.secret-key-file:data/secret.key}") String keyFile) {
    this.repository = repository;
    this.keyFile = Path.of(keyFile).toAbsolutePath().normalize();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<ProcessSecret> findAll() {
    return repository.findAllByOrderByNameAsc();
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public void set(String name, String value, String user) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "Secret names are upper case environment variable names, e.g. BIS_API_KEY");
    }
    if (name.startsWith("SDMXDW_")) {
      throw new IllegalArgumentException("Names starting with SDMXDW_ are reserved");
    }
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("Value is required");
    }
    String encrypted = encrypt(value);
    ProcessSecret existing = repository.findByName(name).orElse(null);
    repository.save(
        new ProcessSecret(
            existing == null ? null : existing.id(), name, encrypted, user, Instant.now()));
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public void delete(String name) {
    repository.findByName(name).ifPresent(repository::delete);
  }

  /** Plain values for a step's environment; unknown names are reported by the caller. */
  Map<String, String> resolve(Collection<String> names) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String name : names) {
      repository.findByName(name).ifPresent(s -> values.put(name, decrypt(s.valueEncrypted())));
    }
    return values;
  }

  String encrypt(String plain) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder()
          .encodeToString(
              ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot encrypt secret", e);
    }
  }

  String decrypt(String stored) {
    try {
      byte[] all = Base64.getDecoder().decode(stored);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, IV_BYTES));
      return new String(
          cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot decrypt secret - was the key file replaced?", e);
    }
  }

  private SecretKeySpec key() {
    if (key == null) {
      synchronized (this) {
        if (key == null) {
          key = new SecretKeySpec(loadOrCreateKey(), "AES");
        }
      }
    }
    return key;
  }

  private byte[] loadOrCreateKey() {
    try {
      if (Files.exists(keyFile)) {
        return Base64.getDecoder().decode(Files.readString(keyFile).trim());
      }
      Files.createDirectories(keyFile.getParent());
      byte[] bytes = new byte[32];
      random.nextBytes(bytes);
      Files.writeString(keyFile, Base64.getEncoder().encodeToString(bytes));
      try {
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // non-POSIX file system: rely on the directory permissions
      }
      return bytes;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
