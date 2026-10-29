package de.sdmxdw.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Links the process folder to a Git repository (#20 phase 2): a sub-folder of a working copy
 * becomes the process root. Saving in the app commits (author = user) and pushes; "pull" brings
 * changes made in an IDE; every run records the commit it ran with. Access via HTTPS with a token
 * (or file:// for local repositories), using JGit - no git binary needed.
 */
@Service
@Order(5)
public class ProcessGitService implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ProcessGitService.class);
  private static final PersonIdent COMMITTER = new PersonIdent("SDMxDW", "sdmxdw@localhost");

  private final JdbcTemplate jdbc;
  private final ProcessFiles files;
  private final ProcessSecretService secrets;
  private final Path gitDir;
  private final Path localRoot;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile Git git;
  private volatile Config config;

  public ProcessGitService(
      JdbcTemplate jdbc,
      ProcessFiles files,
      ProcessSecretService secrets,
      @Value("${sdmxdw.processes.git-dir:data/git}") String gitDir) {
    this.jdbc = jdbc;
    this.files = files;
    this.secrets = secrets;
    this.gitDir = Path.of(gitDir).toAbsolutePath().normalize();
    this.localRoot = files.root();
  }

  /** Repository settings; the token is never returned. */
  public record Config(
      String repoUrl,
      String branch,
      String subFolder,
      String username,
      boolean hasToken,
      Instant lastPullAt,
      String lastMessage,
      String updatedBy,
      Instant updatedAt) {}

  public record CommitInfo(String id, String author, String email, Instant time, String message) {
    public String shortId() {
      return id.substring(0, 8);
    }
  }

  public record Status(Config config, String head, String headMessage, Path workTree) {}

  @Override
  public void run(ApplicationArguments args) {
    Config c = load();
    if (c == null) {
      return;
    }
    try {
      lock.lock();
      openOrClone(c);
      files.setRoot(gitDir.resolve(c.subFolder()));
      config = c;
      log.info("Process folder linked to {} ({}), at {}", c.repoUrl(), c.branch(), currentCommit());
    } catch (RuntimeException e) {
      log.error(
          "Could not open the process repository {} - using the local folder", c.repoUrl(), e);
    } finally {
      lock.unlock();
    }
  }

  public boolean configured() {
    return git != null;
  }

  /** Commit the process folder is at, or null without Git. */
  public String currentCommit() {
    Git g = git;
    if (g == null) {
      return null;
    }
    try {
      ObjectId head = g.getRepository().resolve(Constants.HEAD);
      return head == null ? null : head.name();
    } catch (IOException e) {
      return null;
    }
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public Status status() {
    Config c = config != null ? config : load();
    String head = currentCommit();
    String message = null;
    if (head != null) {
      try {
        message = git.log().setMaxCount(1).call().iterator().next().getShortMessage();
      } catch (GitAPIException e) {
        message = null;
      }
    }
    return new Status(c, head, message, configured() ? gitDir : null);
  }

  /**
   * Links the process folder to a repository: clones it, imports the existing process folders that
   * are not yet in the repository (commit + push) and switches the process root.
   *
   * @param token null = keep the stored token
   */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public Status connect(
      String repoUrl, String branch, String subFolder, String username, String token, String user) {
    String url = required(repoUrl, "Repository URL");
    String br = branch == null || branch.isBlank() ? "main" : branch.strip();
    String sub =
        subFolder == null || subFolder.isBlank()
            ? "processes"
            : subFolder.strip().replaceAll("^/+|/+$", "");
    if (sub.contains("..") || sub.startsWith(".") || !sub.matches("[A-Za-z0-9._/-]+")) {
      throw new IllegalArgumentException("Invalid sub-folder: " + subFolder);
    }
    Config previous = load();
    String encryptedToken =
        token != null && !token.isBlank()
            ? secrets.encrypt(token.strip())
            : previous != null
                ? jdbc.queryForObject(
                    "SELECT token_encrypted FROM process_git_config WHERE id = 1", String.class)
                : null;
    lock.lock();
    try {
      // re-linking: secure the current working copy in the plain folder before it is replaced
      Path oldRoot = files.root();
      if (oldRoot.startsWith(gitDir)) {
        copyAll(oldRoot, localRoot);
        oldRoot = localRoot;
        files.setRoot(localRoot);
      }
      closeQuietly();
      deleteRecursively(gitDir);
      Config c =
          new Config(
              url,
              br,
              sub,
              blankToNull(username),
              encryptedToken != null,
              null,
              null,
              user,
              Instant.now());
      config = c;
      Git cloned = cloneRepository(c, encryptedToken);
      git = cloned;
      Path target = gitDir.resolve(sub);
      Files.createDirectories(target);
      int imported = copyMissingFolders(oldRoot, target);
      String message = "Linked by " + user;
      if (imported > 0 || hasChanges()) {
        commitAll(sub, "Import " + imported + " SDMxDW process folder(s)", user);
        push(encryptedToken);
        message = "Imported " + imported + " process folder(s) into the repository";
      }
      save(c, encryptedToken, message);
      files.setRoot(target);
      files.ensureSharedSdks();
      if (hasChanges()) {
        commitAll(sub, "Add SDMxDW SDKs", user);
        push(encryptedToken);
      }
      config = load();
      return status();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (GitAPIException e) {
      failedConnect();
      throw new IllegalArgumentException("Git: " + e.getMessage(), e);
    } catch (PushRejected e) {
      failedConnect();
      throw new IllegalArgumentException(
          "The repository rejected the initial push: " + e.getMessage(), e);
    } finally {
      lock.unlock();
    }
  }

  /** Unlinks: copies the code back into the local process folder and forgets the repository. */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public void disconnect(String user) {
    lock.lock();
    try {
      if (git != null) {
        Path source = files.root();
        copyAll(source, localRoot);
      }
      files.setRoot(localRoot);
      closeQuietly();
      deleteRecursively(gitDir);
      jdbc.update("DELETE FROM process_git_config");
      config = null;
      log.info("Process folder unlinked from Git by {}", user);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      lock.unlock();
    }
  }

  /** Fetches and rebases onto the remote branch; on conflicts nothing changes. */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public String pull(String user) {
    requireGit();
    lock.lock();
    try {
      String before = currentCommit();
      pullRebase();
      String after = currentCommit();
      String message =
          before != null && before.equals(after)
              ? "Already up to date"
              : "Updated to " + shortId(after);
      jdbc.update(
          "UPDATE process_git_config SET last_pull_at = ?, last_message = ? WHERE id = 1",
          Timestamp.from(Instant.now()),
          message + " (" + user + ")");
      config = load();
      return message;
    } catch (GitAPIException | JGitInternalException e) {
      throw new IllegalArgumentException("Pull failed: " + e.getMessage(), e);
    } finally {
      lock.unlock();
    }
  }

  /** Discards local commits and changes that could not be pushed (recovery after a conflict). */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public void resetToRemote(String user) {
    requireGit();
    lock.lock();
    try {
      git.fetch().setCredentialsProvider(credentials(token())).call();
      git.reset()
          .setMode(ResetCommand.ResetType.HARD)
          .setRef("refs/remotes/origin/" + config.branch())
          .call();
      jdbc.update(
          "UPDATE process_git_config SET last_message = ? WHERE id = 1",
          "Reset to remote by " + user);
      config = load();
    } catch (GitAPIException e) {
      throw new IllegalArgumentException("Reset failed: " + e.getMessage(), e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Commits a changed path below the process root and pushes it. If the remote moved on, the commit
   * is rebased and pushed again; a conflict leaves the commit local and is reported.
   *
   * @param path "folder/file" relative to the process root
   * @return the commit id, or null when no repository is configured
   */
  public String commitAndPush(String path, String message, String user) {
    if (git == null) {
      return null;
    }
    lock.lock();
    try {
      String rel = config.subFolder() + "/" + path;
      git.add().addFilepattern(rel).call();
      git.add().setUpdate(true).addFilepattern(rel).call();
      if (git.status().addPath(rel).call().isClean()) {
        return currentCommit();
      }
      RevCommit commit =
          git.commit()
              .setMessage(message)
              .setAuthor(new PersonIdent(user, user.contains("@") ? user : user + "@sdmxdw"))
              .setCommitter(COMMITTER)
              .call();
      try {
        push(token());
      } catch (PushRejected e) {
        pullRebase();
        push(token());
      }
      return currentCommit() != null ? currentCommit() : commit.name();
    } catch (GitAPIException | PushRejected | JGitInternalException e) {
      throw new IllegalArgumentException(
          "Saved and committed locally, but the push failed: "
              + e.getMessage()
              + " - pull and resolve in your IDE, or reset to the remote state in the Git settings",
          e);
    } finally {
      lock.unlock();
    }
  }

  @PreAuthorize("hasRole('DATENMANAGER')")
  public List<CommitInfo> history(String path, int max) {
    if (git == null) {
      return List.of();
    }
    try {
      List<CommitInfo> result = new ArrayList<>();
      for (RevCommit c :
          git.log().addPath(config.subFolder() + "/" + path).setMaxCount(max).call()) {
        result.add(
            new CommitInfo(
                c.getName(),
                c.getAuthorIdent().getName(),
                c.getAuthorIdent().getEmailAddress(),
                c.getAuthorIdent().getWhenAsInstant(),
                c.getFullMessage().strip()));
      }
      return result;
    } catch (GitAPIException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  /** Unified diff of one file in one commit against its parent. */
  @PreAuthorize("hasRole('DATENMANAGER')")
  public String diff(String commitId, String path) {
    requireGit();
    try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
      RevCommit commit = walk.parseCommit(git.getRepository().resolve(commitId));
      RevCommit parent =
          commit.getParentCount() == 0 ? null : walk.parseCommit(commit.getParent(0).getId());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (DiffFormatter formatter = new DiffFormatter(out)) {
        formatter.setRepository(git.getRepository());
        formatter.setPathFilter(PathFilter.create(config.subFolder() + "/" + path));
        List<DiffEntry> entries =
            formatter.scan(parent == null ? null : parent.getTree(), commit.getTree());
        formatter.format(entries);
      }
      return out.toString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- internals -------------------------------------------------------------------------------

  private void openOrClone(Config c) {
    try {
      if (Files.isDirectory(gitDir.resolve(".git"))) {
        git = Git.open(gitDir.toFile());
      } else {
        git = cloneRepository(c, token());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (GitAPIException e) {
      throw new IllegalStateException(e);
    }
  }

  private Git cloneRepository(Config c, String encryptedToken) throws GitAPIException, IOException {
    Files.createDirectories(gitDir.getParent());
    Git cloned =
        Git.cloneRepository()
            .setURI(c.repoUrl())
            .setDirectory(gitDir.toFile())
            .setCredentialsProvider(credentials(encryptedToken))
            .setBranchesToClone(List.of("refs/heads/" + c.branch()))
            .setBranch("refs/heads/" + c.branch())
            .call();
    // empty repository or new branch: make HEAD point at the configured branch
    if (cloned.getRepository().resolve(Constants.HEAD) == null) {
      RefUpdate update = cloned.getRepository().updateRef(Constants.HEAD);
      update.link("refs/heads/" + c.branch());
    } else if (!c.branch().equals(cloned.getRepository().getBranch())) {
      cloned.checkout().setCreateBranch(true).setName(c.branch()).call();
    }
    return cloned;
  }

  private void pullRebase() throws GitAPIException {
    PullResult result =
        git.pull()
            .setRebase(true)
            .setRemoteBranchName(config.branch())
            .setCredentialsProvider(credentials(token()))
            .call();
    if (!result.isSuccessful()) {
      try {
        git.rebase().setOperation(RebaseCommand.Operation.ABORT).call();
      } catch (GitAPIException ignored) {
        // nothing to abort
      }
      throw new JGitInternalException(
          "conflict with remote changes ("
              + (result.getRebaseResult() == null
                  ? "merge failed"
                  : result.getRebaseResult().getStatus())
              + ")");
    }
  }

  private static final class PushRejected extends Exception {
    PushRejected(String message) {
      super(message);
    }
  }

  private void push(String encryptedToken) throws GitAPIException, PushRejected {
    Iterable<PushResult> results =
        git.push()
            .setRemote("origin")
            .add("HEAD:refs/heads/" + branch())
            .setCredentialsProvider(credentials(encryptedToken))
            .call();
    for (PushResult r : results) {
      for (RemoteRefUpdate u : r.getRemoteUpdates()) {
        if (u.getStatus() != RemoteRefUpdate.Status.OK
            && u.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
          throw new PushRejected(
              u.getStatus() + (u.getMessage() == null ? "" : ": " + u.getMessage()));
        }
      }
    }
  }

  private String branch() {
    if (config != null) {
      return config.branch();
    }
    Config c = load();
    return c == null ? "main" : c.branch();
  }

  private void commitAll(String sub, String message, String user) throws GitAPIException {
    git.add().addFilepattern(sub).call();
    git.add().setUpdate(true).addFilepattern(sub).call();
    git.commit()
        .setMessage(message)
        .setAuthor(new PersonIdent(user, user.contains("@") ? user : user + "@sdmxdw"))
        .setCommitter(COMMITTER)
        .setAllowEmpty(false)
        .call();
  }

  private boolean hasChanges() throws GitAPIException {
    return !git.status().call().isClean();
  }

  private CredentialsProvider credentials(String encryptedToken) {
    if (encryptedToken == null) {
      return null;
    }
    Config c = config != null ? config : load();
    String user = c == null || c.username() == null ? "x-access-token" : c.username();
    return new UsernamePasswordCredentialsProvider(user, secrets.decrypt(encryptedToken));
  }

  private String token() {
    List<String> tokens =
        jdbc.queryForList(
            "SELECT token_encrypted FROM process_git_config WHERE id = 1", String.class);
    return tokens.isEmpty() ? null : tokens.getFirst();
  }

  private Config load() {
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT * FROM process_git_config WHERE id = 1");
    if (rows.isEmpty()) {
      return null;
    }
    Map<String, Object> r = rows.getFirst();
    return new Config(
        (String) r.get("repo_url"),
        (String) r.get("branch"),
        (String) r.get("sub_folder"),
        (String) r.get("username"),
        r.get("token_encrypted") != null,
        r.get("last_pull_at") == null ? null : ((Timestamp) r.get("last_pull_at")).toInstant(),
        (String) r.get("last_message"),
        (String) r.get("updated_by"),
        ((Timestamp) r.get("updated_at")).toInstant());
  }

  private void save(Config c, String encryptedToken, String message) {
    jdbc.update("DELETE FROM process_git_config");
    jdbc.update(
        "INSERT INTO process_git_config (id, repo_url, branch, sub_folder, username, token_encrypted,"
            + " last_message, updated_by, updated_at) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)",
        c.repoUrl(),
        c.branch(),
        c.subFolder(),
        c.username(),
        encryptedToken,
        message,
        c.updatedBy(),
        Timestamp.from(c.updatedAt()));
  }

  /** Linking failed: keep working with the plain folder, forget the half-made link. */
  private void failedConnect() {
    closeQuietly();
    files.setRoot(localRoot);
    config = null;
    jdbc.update("DELETE FROM process_git_config");
  }

  private void requireGit() {
    if (git == null) {
      throw new IllegalArgumentException("No Git repository is linked");
    }
  }

  private void closeQuietly() {
    if (git != null) {
      git.close();
      git = null;
    }
  }

  /** Copies process folders (and shared/) that do not exist in the target yet. */
  private static int copyMissingFolders(Path source, Path target) throws IOException {
    if (!Files.isDirectory(source)) {
      return 0;
    }
    int copied = 0;
    try (Stream<Path> dirs = Files.list(source)) {
      for (Path dir : dirs.filter(Files::isDirectory).toList()) {
        String name = dir.getFileName().toString();
        if (name.startsWith(".") || Files.exists(target.resolve(name))) {
          continue;
        }
        copyAll(dir, target.resolve(name));
        copied++;
      }
    }
    return copied;
  }

  private static void copyAll(Path source, Path target) throws IOException {
    if (!Files.isDirectory(source)) {
      return;
    }
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            String name = dir.getFileName().toString();
            if (!dir.equals(source) && (name.startsWith(".") || name.equals("__pycache__"))) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            Files.createDirectories(target.resolve(source.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(
                file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  private static String shortId(String id) {
    return id == null ? "-" : id.substring(0, Math.min(8, id.length()));
  }

  private static String required(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    return value.strip();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
