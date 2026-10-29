package de.sdmxdw.web.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.data.observation.ObservationStage;
import de.sdmxdw.security.AppUserPrincipal;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
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

/**
 * The explorer's dataflow page for both roles: economists only ever see published data,
 * Datenmanager get pending changes, promotion and correction.
 */
@AutoConfigureMockMvc
class ExploreDataflowPageTest extends AbstractIntegrationTest {

  private static int counter;

  @Autowired private MockMvc mvc;
  @Autowired private ConceptSchemeService conceptSchemeService;
  @Autowired private CodelistService codelistService;
  @Autowired private DataStructureDefinitionService dsdService;
  @Autowired private DataflowService dataflowService;
  @Autowired private ObservationService observationService;

  /** Runs service calls as Datenmanager without leaking the identity into MockMvc requests. */
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

  /** CH and DE series, 2025 published as 100; then Alpha: CH 2025 corrected to 111, DE 2026 new. */
  private Dataflow dataflowWithPendingChanges() {
    String suffix = "EXP" + (++counter) + "_" + System.nanoTime() % 100000;
    return asDatenmanager(
        () -> {
          ConceptScheme cs =
              conceptSchemeService.createScheme("TST", "CS_" + suffix, "1.0.0", "Concepts");
          for (String c : List.of("CTY", "TIME_PERIOD", "OBS_VALUE")) {
            cs = conceptSchemeService.addConcept(cs.id(), c, c);
          }
          Codelist cl = codelistService.createCodelist("TST", "CL_" + suffix, "1.0.0", "Countries");
          cl = codelistService.addCode(cl.id(), "CH", "Switzerland");
          cl = codelistService.addCode(cl.id(), "DE", "Germany");
          DataStructureDefinition dsd =
              dsdService.createDsd("TST", "DSD_" + suffix, "1.0.0", "DSD");
          dsd = dsdService.addDimension(dsd.id(), id(cs, "CTY"), cl.id(), DataType.STRING);
          dsd = dsdService.addDimension(dsd.id(), id(cs, "TIME_PERIOD"), null, DataType.DATE);
          dsd = dsdService.addMeasure(dsd.id(), id(cs, "OBS_VALUE"), DataType.NUMBER);
          Dataflow df =
              dataflowService.createDataflow("TST", "DF_" + suffix, "1.0.0", "Flow", dsd.id());
          for (String cty : List.of("CH", "DE")) {
            observationService.recordObservationByConceptCode(
                df.id(),
                Map.of("CTY", cty, "TIME_PERIOD", "2025"),
                Map.of("OBS_VALUE", "100"),
                "dm");
          }
          observationService.promoteToBeta(df.id(), "dm");
          observationService.recordObservationByConceptCode(
              df.id(),
              Map.of("CTY", "CH", "TIME_PERIOD", "2025"),
              Map.of("OBS_VALUE", "111"),
              "dm");
          observationService.recordObservationByConceptCode(
              df.id(),
              Map.of("CTY", "DE", "TIME_PERIOD", "2026"),
              Map.of("OBS_VALUE", "222"),
              "dm");
          return df;
        });
  }

  /** Real principal: the navigation renders its initials, the filters inspect it. */
  private static RequestPostProcessor principal(String email, AppUserRole role) {
    return user(
        new AppUserPrincipal(
            new AppUser(1L, "Test", "User", email, "x", role, false, Instant.now())));
  }

  private static Long id(ConceptScheme cs, String code) {
    return cs.concepts().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().id();
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void economistsOnlySeePublishedData() throws Exception {
    Dataflow df = dataflowWithPendingChanges();
    String base = "/explore/dataflows/" + df.id();
    RequestPostProcessor oekonom = principal("oe@x", AppUserRole.OEKONOM);

    // asking for Alpha / pending falls back to the published series list
    String page = body(get(base).param("view", "pending").param("stage", "ALPHA").with(oekonom));
    assertThat(page).doesNotContain("Pending changes").doesNotContain("Unpublished (Alpha)");
    assertThat(body(get(base).param("view", "observations").param("stage", "ALPHA").with(oekonom)))
        .contains(">100<")
        .doesNotContain(">111<")
        .doesNotContain(">222<");
    // facets of the Alpha-only 2026 observation are not leaked either
    assertThat(body(get(base + "/facets").param("stage", "ALPHA").with(oekonom)))
        .contains("\"total\":2");

    mvc.perform(post(base + "/promote").with(oekonom).with(csrf()))
        .andExpect(status().isForbidden());
    Long seriesId = observationService.findSeriesByDataflow(df.id()).getFirst().id();
    mvc.perform(
            post(base + "/series/" + seriesId + "/correct")
                .param("timePeriod", "2025")
                .param("measure_1", "1")
                .with(oekonom)
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void datenmanagerSeesPendingChangesPromotesAndCorrects() throws Exception {
    Dataflow df = dataflowWithPendingChanges();
    String base = "/explore/dataflows/" + df.id();
    RequestPostProcessor dm = principal("dm@x", AppUserRole.DATENMANAGER);

    String pending = body(get(base).param("view", "pending").with(dm));
    assertThat(pending)
        .contains("Promote 2 change(s) to Beta")
        .contains("2 observation(s) would be published")
        .contains(">100</span>") // published value of the correction, struck through
        .contains("<b>111</b>")
        .contains(">new</span>")
        .contains("<b>222</b>");
    // key filter applies to pending changes as well
    assertThat(body(get(base).param("view", "pending").param("k_CTY", "DE").with(dm)))
        .contains("1 observation(s) would be published")
        .doesNotContain("<b>111</b>");

    mvc.perform(post(base + "/promote").with(dm).with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(asDatenmanager(() -> observationService.countPendingChanges(df.id()))).isZero();
    assertThat(body(get(base).param("view", "observations").param("stage", "ALPHA").with(dm)))
        .contains(">111<")
        .contains(">222<");

    // correction in the series value table lands in Alpha and shows up as pending
    Long chSeries =
        observationService.findSeriesByDataflow(df.id()).stream()
            .filter(s -> s.seriesKey().equals("CH"))
            .findFirst()
            .orElseThrow()
            .id();
    Long measureId = dsdService.findById(df.dsdId()).measures().iterator().next().id();
    String table =
        body(
            post(base + "/series/" + chSeries + "/correct")
                .param("timePeriod", "2025")
                .param("measure_" + measureId, "115")
                .param("comment", "late booking")
                .with(dm)
                .with(csrf()));
    assertThat(table).contains(">115<").contains("pending, now 111").contains("saved");
    // Beta keeps the published value until the next promotion
    assertThat(
            asDatenmanager(
                () ->
                    observationService
                        .findCurrentOne(df.id(), chSeries, "2025", ObservationStage.BETA)
                        .orElseThrow()
                        .measureValuesJson()))
        .contains("111");
    assertThat(body(get(base).param("view", "activity").with(dm)))
        .contains("PROMOTE_ALPHA_TO_BETA");
  }
}
