package de.sdmxdw.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.sdmxdw.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** Linking the process folder to a (local, bare) Git repository. */
@WithMockUser(username = "dm@x", roles = "DATENMANAGER")
class ProcessGitServiceTest extends AbstractIntegrationTest {

  @Autowired private ProcessGitService gitService;
  @Autowired private ProcessCodeService codeService;
  @Autowired private ProcessService processService;
  @Autowired private ProcessRunRepository runRepository;
  @Autowired private ProcessFiles files;

  @TempDir Path temp;

  private List<RevCommit> remoteLog(Path bare) throws Exception {
    try (Git g = Git.open(bare.toFile())) {
      return List.copyOf((java.util.Collection<RevCommit>) iterate(g.log().call()));
    }
  }

  private static java.util.List<RevCommit> iterate(Iterable<RevCommit> it) {
    java.util.List<RevCommit> list = new java.util.ArrayList<>();
    it.forEach(list::add);
    return list;
  }

  @Test
  void linkCommitPushPullAndUnlink() throws Exception {
    Path bare = temp.resolve("remote.git");
    // the remote already has a README outside the process folder and a branch "main"
    Path seed = temp.resolve("seed");
    try (Git g = Git.init().setDirectory(seed.toFile()).setInitialBranch("main").call()) {
      Files.writeString(seed.resolve("README.md"), "# repo\n");
      g.add().addFilepattern("README.md").call();
      g.commit()
          .setMessage("init")
          .setAuthor(new PersonIdent("x", "x@x"))
          .setCommitter(new PersonIdent("x", "x@x"))
          .call();
    }
    Git.cloneRepository()
        .setURI(seed.toUri().toString())
        .setDirectory(bare.toFile())
        .setBare(true)
        .call()
        .close();

    String sfx = ProcessFixtures.suffix();
    Process p = processService.create("Git test " + sfx, null, null, "dm@x");
    Path localRoot = files.root();
    try {
      gitService.connect(bare.toUri().toString(), "main", "sdmxdw/processes", null, null, "dm@x");
      assertThat(gitService.configured()).isTrue();
      assertThat(files.root().toString()).contains("test-git").endsWith("sdmxdw/processes");
      assertThat(files.exists(p.folder(), "main.py")).isTrue();
      assertThat(files.exists(ProcessFiles.SHARED, "sdmxdw_sdk.py")).isTrue();
      assertThat(remoteLog(bare).getFirst().getFullMessage()).startsWith("Import");

      // save in the app: commit with the user as author, pushed
      String commit =
          codeService.save(p.folder(), "main.py", "print('from git')\n", "Change greeting", "dm@x");
      assertThat(commit).isNotNull();
      RevCommit pushed = remoteLog(bare).getFirst();
      assertThat(pushed.getName()).isEqualTo(commit);
      assertThat(pushed.getFullMessage()).isEqualTo("Change greeting");
      assertThat(pushed.getAuthorIdent().getEmailAddress()).isEqualTo("dm@x");
      assertThat(gitService.history(p.folder() + "/main.py", 10))
          .extracting(ProcessGitService.CommitInfo::message)
          .containsExactly(
              "Change greeting", gitService.history(p.folder() + "/main.py", 10).get(1).message());
      assertThat(gitService.diff(commit, p.folder() + "/main.py")).contains("+print('from git')");

      // someone changes the code in an IDE and pushes; pull brings it in
      Path ide = temp.resolve("ide");
      try (Git g =
          Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(ide.toFile()).call()) {
        Files.writeString(
            ide.resolve("sdmxdw/processes/" + p.folder() + "/helper.py"), "VALUE = 42\n");
        g.add().addFilepattern(".").call();
        g.commit()
            .setMessage("Add helper in IDE")
            .setAuthor(new PersonIdent("ide", "ide@x"))
            .setCommitter(new PersonIdent("ide", "ide@x"))
            .call();
        g.push().call();
      }
      assertThat(gitService.pull("dm@x")).startsWith("Updated to");
      assertThat(files.read(p.folder(), "helper.py")).isEqualTo("VALUE = 42\n");

      // a run records the commit it ran with
      ProcessRun run = processService.startRun(p.id(), Map.of(), false, "dm@x");
      Instant until = Instant.now().plus(Duration.ofSeconds(60));
      while (!runRepository.findById(run.id()).orElseThrow().status().finished()
          && Instant.now().isBefore(until)) {
        Thread.sleep(200);
      }
      assertThat(runRepository.findById(run.id()).orElseThrow().commitSha())
          .isEqualTo(gitService.currentCommit());

      // remote moved on with a non-conflicting change: the app's save is rebased and pushed
      try (Git g = Git.open(ide.toFile())) {
        g.pull().call();
        Files.writeString(ide.resolve("README.md"), "# repo 2\n");
        g.add().addFilepattern(".").call();
        g.commit()
            .setMessage("Readme")
            .setAuthor(new PersonIdent("ide", "ide@x"))
            .setCommitter(new PersonIdent("ide", "ide@x"))
            .call();
        g.push().call();
      }
      codeService.save(p.folder(), "main.py", "print('rebased')\n", "After remote change", "dm@x");
      assertThat(remoteLog(bare))
          .extracting(RevCommit::getFullMessage)
          .startsWith("After remote change", "Readme");

      // conflicting change of the same line: reported, recovery by reset
      try (Git g = Git.open(ide.toFile())) {
        g.pull().call();
        Files.writeString(
            ide.resolve("sdmxdw/processes/" + p.folder() + "/main.py"), "print('ide wins')\n");
        g.add().addFilepattern(".").call();
        g.commit()
            .setMessage("IDE edit")
            .setAuthor(new PersonIdent("ide", "ide@x"))
            .setCommitter(new PersonIdent("ide", "ide@x"))
            .call();
        g.push().call();
      }
      assertThatThrownBy(
              () ->
                  codeService.save(p.folder(), "main.py", "print('app')\n", "Conflicting", "dm@x"))
          .hasMessageContaining("push failed");
      gitService.resetToRemote("dm@x");
      assertThat(files.read(p.folder(), "main.py")).isEqualTo("print('ide wins')\n");
    } finally {
      gitService.disconnect("dm@x");
    }
    assertThat(gitService.configured()).isFalse();
    assertThat(files.root()).isEqualTo(localRoot);
    assertThat(files.read(p.folder(), "main.py")).isEqualTo("print('ide wins')\n");
  }

  @Test
  void invalidRepositoriesLeaveTheLocalFolderInUse() {
    Path root = files.root();
    assertThatThrownBy(
            () ->
                gitService.connect(
                    temp.resolve("nope.git").toUri().toString(),
                    "main",
                    "processes",
                    null,
                    null,
                    "dm@x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(gitService.configured()).isFalse();
    assertThat(files.root()).isEqualTo(root);
    assertThatThrownBy(
            () -> gitService.connect("file:///x", "main", "../escape", null, null, "dm@x"))
        .hasMessageContaining("Invalid sub-folder");
  }
}
