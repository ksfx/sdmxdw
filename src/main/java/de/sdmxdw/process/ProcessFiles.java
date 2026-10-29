package de.sdmxdw.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * File access to the process folder: one sub-folder per process plus {@code shared/} with the SDKs.
 * All paths are validated to stay inside the process' own folder; hidden files and folders (.git,
 * .venv, ...) and caches are never listed or touched. The root can be re-pointed into a Git working
 * copy (see {@link ProcessGitService}).
 */
@Component
public class ProcessFiles {

  public static final String SHARED = "shared";
  static final Pattern FOLDER = Pattern.compile("[a-z0-9][a-z0-9_-]{0,99}");
  private static final Set<String> EDITABLE =
      Set.of("py", "r", "sql", "yaml", "yml", "json", "csv", "txt", "md", "toml", "cfg", "ini");
  private static final Set<String> IGNORED_DIRS = Set.of("__pycache__", "node_modules");
  private static final long MAX_EDIT_BYTES = 1_000_000;

  private volatile Path root;

  public ProcessFiles(@Value("${sdmxdw.processes.dir:processes}") String rootDir) {
    this.root = Path.of(rootDir).toAbsolutePath().normalize();
  }

  public Path root() {
    return root;
  }

  /** Used when the process folder moves into a Git working copy. */
  void setRoot(Path newRoot) {
    this.root = newRoot.toAbsolutePath().normalize();
  }

  public Path folderPath(String folder) {
    if (!SHARED.equals(folder) && !FOLDER.matcher(folder).matches()) {
      throw new IllegalArgumentException("Invalid process folder: " + folder);
    }
    return root.resolve(folder);
  }

  /** Creates the folder with a starter script if it does not exist yet. */
  public void ensureFolder(String folder, String starterScriptName, String starterContent) {
    Path dir = folderPath(folder);
    try {
      Files.createDirectories(dir);
      if (starterScriptName != null && isEmpty(dir)) {
        Files.writeString(dir.resolve(starterScriptName), starterContent, StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Copies the bundled SDKs into {@code shared/} unless they exist (user changes win). */
  public void ensureSharedSdks() {
    Path shared = root.resolve(SHARED);
    try {
      Files.createDirectories(shared);
      for (String name : List.of("sdmxdw_sdk.py", "sdmxdw.R", "README.md")) {
        Path target = shared.resolve(name);
        if (!Files.exists(target)) {
          try (InputStream in = getClass().getResourceAsStream("/process-sdk/" + name)) {
            if (in != null) {
              Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public boolean exists(String folder, String relativePath) {
    return Files.isRegularFile(resolve(folder, relativePath));
  }

  /** All visible files of a folder, relative paths with "/" separators, sorted. */
  public List<FileEntry> list(String folder) {
    Path dir = folderPath(folder);
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    List<FileEntry> result = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> visible(dir.relativize(p)))
          .forEach(
              p -> {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                try {
                  result.add(new FileEntry(rel, Files.size(p), editable(rel)));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    result.sort(Comparator.comparing(FileEntry::path));
    return result;
  }

  public String read(String folder, String relativePath) {
    Path file = resolve(folder, relativePath);
    try {
      if (Files.size(file) > MAX_EDIT_BYTES) {
        throw new IllegalArgumentException("File is too large to edit in the browser");
      }
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void write(String folder, String relativePath, String content) {
    if (!editable(relativePath)) {
      throw new IllegalArgumentException(
          "Only text files can be edited (" + String.join(", ", EDITABLE) + ")");
    }
    if (content.length() > MAX_EDIT_BYTES) {
      throw new IllegalArgumentException("File is too large");
    }
    Path file = resolve(folder, relativePath);
    try {
      Files.createDirectories(file.getParent());
      // normalise line endings coming from the browser
      Files.writeString(file, content.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void delete(String folder, String relativePath) {
    try {
      Files.deleteIfExists(resolve(folder, relativePath));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void rename(String folder, String from, String to) {
    Path source = resolve(folder, from);
    Path target = resolve(folder, to);
    if (!editable(to)) {
      throw new IllegalArgumentException("Unsupported file type: " + to);
    }
    try {
      Files.createDirectories(target.getParent());
      Files.move(source, target);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Resolves a path inside a process folder. Rejects absolute paths, "..", hidden segments and
   * anything that would leave the folder.
   */
  Path resolve(String folder, String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      throw new IllegalArgumentException("File path is required");
    }
    String rel = relativePath.trim().replace('\\', '/');
    if (rel.startsWith("/") || rel.contains("..") || rel.contains("//")) {
      throw new IllegalArgumentException("Invalid file path: " + relativePath);
    }
    Path dir = folderPath(folder);
    Path resolved = dir.resolve(rel).normalize();
    if (!resolved.startsWith(dir) || resolved.equals(dir) || !visible(dir.relativize(resolved))) {
      throw new IllegalArgumentException("Invalid file path: " + relativePath);
    }
    return resolved;
  }

  private static boolean visible(Path relative) {
    for (Path segment : relative) {
      String name = segment.toString();
      if (name.startsWith(".") || IGNORED_DIRS.contains(name)) {
        return false;
      }
    }
    return true;
  }

  static boolean editable(String path) {
    int dot = path.lastIndexOf('.');
    return dot > 0 && EDITABLE.contains(path.substring(dot + 1).toLowerCase());
  }

  private static boolean isEmpty(Path dir) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.findAny().isEmpty();
    }
  }

  public record FileEntry(String path, long size, boolean editable) {

    public String language() {
      String p = path.toLowerCase();
      if (p.endsWith(".py")) {
        return "python";
      }
      if (p.endsWith(".r")) {
        return "r";
      }
      if (p.endsWith(".sql")) {
        return "sql";
      }
      if (p.endsWith(".yaml") || p.endsWith(".yml")) {
        return "yaml";
      }
      if (p.endsWith(".json")) {
        return "json";
      }
      return "text";
    }
  }
}
