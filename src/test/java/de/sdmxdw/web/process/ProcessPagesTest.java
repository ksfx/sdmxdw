package de.sdmxdw.web.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.process.ProcessFiles;
import de.sdmxdw.process.ProcessRepository;
import de.sdmxdw.security.AppUserPrincipal;
import de.sdmxdw.user.AppUser;
import de.sdmxdw.user.AppUserRole;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@AutoConfigureMockMvc
class ProcessPagesTest extends AbstractIntegrationTest {

  private static int counter;

  @Autowired private MockMvc mvc;
  @Autowired private ProcessRepository processRepository;
  @Autowired private ProcessFiles files;

  private static RequestPostProcessor as(AppUserRole role) {
    return user(
        new AppUserPrincipal(
            new AppUser(
                1L, "T", "U", role.name().toLowerCase() + "@x", "x", role, false, Instant.now())));
  }

  private String body(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    return mvc.perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void economistsGetForbidden() throws Exception {
    RequestPostProcessor oe = as(AppUserRole.OEKONOM);
    mvc.perform(get("/processes").with(oe)).andExpect(status().isForbidden());
    mvc.perform(get("/processes/secrets").with(oe)).andExpect(status().isForbidden());
    mvc.perform(post("/processes").param("name", "x").with(oe).with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void datenmanagerCreatesEditsAndRunsAProcess() throws Exception {
    RequestPostProcessor dm = as(AppUserRole.DATENMANAGER);
    String name = "Pages test " + (++counter) + " " + System.nanoTime() % 100000;
    String location =
        mvc.perform(post("/processes").param("name", name).with(dm).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    assertThat(location).contains("tab=code");
    var process = processRepository.findByName(name).orElseThrow();
    String base = "/processes/" + process.id();

    assertThat(body(get("/processes").with(dm))).contains(name);
    assertThat(body(get(base).with(dm))).contains("step-graph").contains("Save steps");
    assertThat(body(get(base).param("tab", "code").param("file", "main.py").with(dm)))
        .contains("/js/vendor/codemirror.js")
        .contains("data-language=\"python\"");
    assertThat(body(get(base).param("tab", "settings").with(dm))).contains("Run parameters");
    assertThat(body(get(base).param("tab", "runs").with(dm))).contains("No runs yet");

    mvc.perform(
            post(base + "/files")
                .param("path", "main.py")
                .param("content", "print('edited')\n")
                .with(dm)
                .with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(files.read(process.folder(), "main.py")).isEqualTo("print('edited')\n");
    mvc.perform(
            post(base + "/files")
                .param("path", "../escape.py")
                .param("content", "x")
                .with(dm)
                .with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(files.root().getParent().resolve("escape.py")).doesNotExist();

    mvc.perform(
            post(base + "/steps")
                .param("steps[0].stepKey", "main")
                .param("steps[0].type", "SCRIPT")
                .param("steps[0].scriptPath", "main.py")
                .param("steps[0].args", "--x 1")
                .param("steps[0].needs", "")
                .with(dm)
                .with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(processRepository.findById(process.id()).orElseThrow().steps().getFirst().args())
        .isEqualTo("--x 1");

    String runLocation =
        mvc.perform(post(base + "/run").with(dm).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    assertThat(body(get(runLocation).with(dm))).contains("Run #");
    assertThat(body(get("/processes/shared").with(dm))).contains("sdmxdw_sdk.py");
    assertThat(body(get("/processes/secrets").with(dm))).contains("Add or replace");
  }
}
