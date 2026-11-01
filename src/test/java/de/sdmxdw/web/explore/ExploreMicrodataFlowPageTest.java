package de.sdmxdw.web.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.microdata.FacetSlot;
import de.sdmxdw.microdata.FieldDataType;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataRecord;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.microdata.MicrodataStage;
import de.sdmxdw.security.AppUserPrincipal;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.user.AppUser;
import de.sdmxdw.user.AppUserRole;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The explorer's microdata flow and record pages for both roles (#19, #16). */
@AutoConfigureMockMvc
class ExploreMicrodataFlowPageTest extends AbstractIntegrationTest {

  private static int counter;

  @Autowired private MockMvc mvc;
  @Autowired private MicrodataFlowService flowService;
  @Autowired private MicrodataRecordService recordService;
  @Autowired private CodelistService codelistService;

  private final RequestPostProcessor oekonom = principal("oe@x", AppUserRole.OEKONOM);
  private final RequestPostProcessor dm = principal("dm@x", AppUserRole.DATENMANAGER);

  private static RequestPostProcessor principal(String email, AppUserRole role) {
    return user(
        new AppUserPrincipal(
            new AppUser(1L, "Test", "User", email, "x", role, false, Instant.now())));
  }

  private static <T> T asDatenmanager(Supplier<T> action) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "setup", null, List.of(new SimpleGrantedAuthority("ROLE_DATENMANAGER"))));
    try {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
      TestSecurityContextHolder.clearContext();
    }
  }

  /** L-1 (CHF 100) and L-2 (EUR 200) published; then L-3 recorded only in Alpha. */
  private MicrodataFlow flow() {
    String suffix = "MFP" + (++counter) + "_" + System.nanoTime() % 100000;
    return asDatenmanager(
        () -> {
          MicrodataFlow flow =
              flowService.createFlow("TST", "MDF_" + suffix, "1.0.0", "Loans", null);
          Codelist ccy = codelistService.createCodelist("TST", "CL_" + suffix, "1.0.0", "Ccy");
          ccy = codelistService.addCode(ccy.id(), "CHF", "Swiss franc");
          ccy = codelistService.addCode(ccy.id(), "EUR", "Euro");
          flowService.addField(
              flow.id(),
              "CURRENCY",
              "Currency",
              FieldDataType.STRING,
              ccy.id(),
              true,
              FacetSlot.facet1);
          flowService.addField(
              flow.id(), "AMOUNT", "Amount", FieldDataType.NUMBER, null, true, null);
          recordService.ingestRecord(
              flow.id(), "L-1", "2026-Q3", Map.of("CURRENCY", "CHF", "AMOUNT", "100"), "import");
          recordService.ingestRecord(
              flow.id(), "L-2", "2026-Q3", Map.of("CURRENCY", "EUR", "AMOUNT", "200"), "import");
          recordService.promoteToBeta(flow.id(), "dm");
          recordService.ingestRecord(
              flow.id(), "L-3", "2026-Q3", Map.of("CURRENCY", "CHF", "AMOUNT", "300"), "import");
          return flow;
        });
  }

  private MicrodataRecord current(MicrodataFlow flow, String entity, MicrodataStage stage) {
    return asDatenmanager(
        () -> recordService.findCurrentOne(flow.id(), entity, "2026-Q3", stage).orElseThrow());
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void economistsOnlySeePublishedRecords() throws Exception {
    MicrodataFlow flow = flow();
    String base = "/explore/microdata/" + flow.id();

    String page = body(get(base).param("stage", "ALPHA").param("view", "pending").with(oekonom));
    assertThat(page)
        .contains("2 record(s)")
        .contains(">L-1<")
        .doesNotContain(">L-3<")
        .doesNotContain("Pending changes")
        .doesNotContain("Unpublished (Alpha)");
    assertThat(body(get(base).param("f_CURRENCY", "EUR").with(oekonom)))
        .contains("1 record(s)")
        .contains(">L-2<");
    assertThat(body(get(base + "/facets").param("stage", "ALPHA").with(oekonom)))
        .contains("\"total\":2");

    MicrodataRecord alpha = current(flow, "L-3", MicrodataStage.ALPHA);
    mvc.perform(get(base + "/records/" + alpha.id()).with(oekonom))
        .andExpect(status().isForbidden());
    MicrodataRecord published = current(flow, "L-1", MicrodataStage.BETA);
    assertThat(body(get(base + "/records/" + published.id()).with(oekonom)))
        .contains("Published (Beta)")
        .doesNotContain(">Edit<");
    mvc.perform(post(base + "/promote").with(oekonom).with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(base + "/records/" + published.id() + "/correct")
                .param("v.CURRENCY", "EUR")
                .param("v.AMOUNT", "1")
                .with(oekonom)
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void datenmanagerCorrectsReviewsAndPromotes() throws Exception {
    MicrodataFlow flow = flow();
    String base = "/explore/microdata/" + flow.id();

    assertThat(body(get(base).param("view", "pending").with(dm)))
        .contains("Promote 1 record(s) to Beta")
        .contains(">L-3<")
        .contains("new record");

    MicrodataRecord l1 = current(flow, "L-1", MicrodataStage.ALPHA);
    assertThat(body(get(base + "/records/" + l1.id() + "/edit").with(dm)))
        .contains("name=\"v.CURRENCY\"")
        .contains("name=\"v.AMOUNT\"")
        .contains("value=\"100\"");

    // invalid value: the form comes back with the message and the entered values
    assertThat(
            body(
                post(base + "/records/" + l1.id() + "/correct")
                    .param("v.CURRENCY", "EUR")
                    .param("v.AMOUNT", "")
                    .with(dm)
                    .with(csrf())))
        .contains("Missing required field AMOUNT")
        .contains("alert-error");

    String location =
        mvc.perform(
                post(base + "/records/" + l1.id() + "/correct")
                    .param("v.CURRENCY", "EUR")
                    .param("v.AMOUNT", "150")
                    .param("comment", "late booking")
                    .with(dm)
                    .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andReturn()
            .getResponse()
            .getRedirectedUrl();
    assertThat(body(get(location).with(dm)))
        .contains("Reason: late booking")
        .contains("was CHF")
        .contains("was 100");
    assertThat(body(get(base).param("view", "pending").with(dm)))
        .contains("Promote 2 record(s) to Beta")
        .contains("CURRENCY: CHF -&gt; EUR; AMOUNT: 100 -&gt; 150");

    mvc.perform(post(base + "/promote").with(dm).with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(body(get(base).param("f_CURRENCY", "EUR").with(oekonom))).contains("2 record(s)");
    assertThat(body(get(base).param("view", "activity").with(dm)))
        .contains("PROMOTE_ALPHA_TO_BETA")
        .contains("CORRECT_RECORD");
  }

  @Test
  void documentationIsShownToEveryoneAndEditedByDatenmanager() throws Exception {
    MicrodataFlow flow = flow();
    String base = "/explore/microdata/" + flow.id();
    Long currencyId =
        asDatenmanager(
            () ->
                flowService.findFields(flow.id()).stream()
                    .filter(f -> f.fieldCode().equals("CURRENCY"))
                    .findFirst()
                    .orElseThrow()
                    .id());

    // the economist sees the tab but no edit forms
    assertThat(body(get(base).param("view", "documentation").with(oekonom)))
        .contains("Documentation")
        .contains("id=\"field-CURRENCY\"")
        .contains("Swiss franc")
        .doesNotContain("Edit documentation");
    mvc.perform(
            post(base + "/fields/" + currencyId + "/documentation")
                .param("description", "x")
                .with(oekonom)
                .with(csrf()))
        .andExpect(status().isForbidden());

    mvc.perform(
            post(base + "/documentation")
                .param("description", "Loans of the **demo** bank.\n\nSecond paragraph.")
                .with(dm)
                .with(csrf()))
        .andExpect(status().is3xxRedirection());
    mvc.perform(
            post(base + "/fields/" + currencyId + "/documentation")
                .param("description", "Currency of the *loan*. Further text.")
                .param("attributes", "Field id: XY-01\nTechnical name: ccy")
                .with(dm)
                .with(csrf()))
        .andExpect(status().is3xxRedirection());
    mvc.perform(
            post(base + "/fields/" + currencyId + "/documentation")
                .param("attributes", "no colon here")
                .with(dm)
                .with(csrf()))
        .andExpect(status().is3xxRedirection());

    String doc = body(get(base).param("view", "documentation").with(oekonom));
    assertThat(doc)
        .contains("Loans of the <strong>demo</strong> bank.")
        .contains("Second paragraph.")
        .contains("Currency of the <em>loan</em>.")
        .contains("XY-01")
        .contains("Technical name");
    // the header only shows the first paragraph
    assertThat(body(get(base).with(oekonom)))
        .contains("Loans of the <strong>demo</strong> bank.")
        .doesNotContain("Second paragraph.");
    // the record page links the label to the definition, with its first sentence as tooltip
    MicrodataRecord published = current(flow, "L-1", MicrodataStage.BETA);
    assertThat(body(get(base + "/records/" + published.id()).with(oekonom)))
        .contains("#field-CURRENCY")
        .contains("CURRENCY: Currency of the loan.");
    assertThat(body(get(base).param("view", "activity").with(dm)))
        .contains("Documentation of field CURRENCY updated");
  }
}
