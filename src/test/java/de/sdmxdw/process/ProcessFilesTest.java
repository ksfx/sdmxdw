package de.sdmxdw.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessFilesTest {

  @TempDir Path root;

  @Test
  void staysInsideTheProcessFolder() {
    ProcessFiles files = new ProcessFiles(root.toString());
    files.ensureFolder("kred", "main.py", "print(1)");
    for (String bad :
        new String[] {
          "../other/x.py", "/etc/passwd", "a/../../x.py", ".git/config", "lib/.hidden.py", ""
        }) {
      assertThatThrownBy(() -> files.resolve("kred", bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> files.folderPath("../x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> files.folderPath("Upper"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(files.resolve("kred", "lib/parse.py")).isEqualTo(root.resolve("kred/lib/parse.py"));
  }

  @Test
  void listsVisibleFilesAndEditsText() throws Exception {
    ProcessFiles files = new ProcessFiles(root.toString());
    files.ensureFolder("kred", "main.py", "print(1)");
    files.write("kred", "lib/parse.py", "x = 1\r\n");
    Files.createDirectories(root.resolve("kred/__pycache__"));
    Files.writeString(root.resolve("kred/__pycache__/main.cpython.pyc"), "bin");
    Files.createDirectories(root.resolve("kred/.venv"));
    Files.writeString(root.resolve("kred/.venv/x.py"), "");
    Files.write(root.resolve("kred/data.bin"), new byte[] {1, 2});

    assertThat(files.list("kred"))
        .extracting(ProcessFiles.FileEntry::path)
        .containsExactly("data.bin", "lib/parse.py", "main.py");
    assertThat(files.read("kred", "lib/parse.py")).isEqualTo("x = 1\n");
    assertThat(files.list("kred").getFirst().editable()).isFalse();
    assertThatThrownBy(() -> files.write("kred", "tool.exe", "x"))
        .isInstanceOf(IllegalArgumentException.class);

    files.rename("kred", "lib/parse.py", "lib/parser.R");
    assertThat(files.exists("kred", "lib/parser.R")).isTrue();
    assertThat(
            files.list("kred").stream()
                .filter(f -> f.path().endsWith(".R"))
                .findFirst()
                .orElseThrow()
                .language())
        .isEqualTo("r");
  }

  @Test
  void bundledSdksAreCopiedButNeverOverwritten() throws Exception {
    ProcessFiles files = new ProcessFiles(root.toString());
    files.ensureSharedSdks();
    assertThat(root.resolve("shared/sdmxdw_sdk.py")).exists();
    assertThat(root.resolve("shared/sdmxdw.R")).exists();
    Files.writeString(root.resolve("shared/sdmxdw_sdk.py"), "# changed");
    files.ensureSharedSdks();
    assertThat(Files.readString(root.resolve("shared/sdmxdw_sdk.py"))).isEqualTo("# changed");
  }

  @Test
  void argumentsAreSplitWithQuotesAndParametersSubstituted() {
    assertThat(ProcessRunner.splitArgs("--flow SNB:DF_KRED(3.0.0)  --name 'A B' \"C D\""))
        .containsExactly("--flow", "SNB:DF_KRED(3.0.0)", "--name", "A B", "C D");
    assertThat(
            ProcessRunner.substitute(
                "--period ${period} --x ${unknown}", java.util.Map.of("period", "2026-Q3")))
        .isEqualTo("--period 2026-Q3 --x ");
  }
}
