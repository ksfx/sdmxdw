package de.sdmxdw.web.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.security.ApiTokenService;
import de.sdmxdw.security.AppUserPrincipal;
import de.sdmxdw.structure.annotation.AnnotationService;
import de.sdmxdw.structure.annotation.AnnotationTarget;
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
import de.sdmxdw.user.AppUserService;
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

/** SDMX annotations on structures and the table view of the explorer (#15). */
@AutoConfigureMockMvc
class TableViewTest extends AbstractIntegrationTest {

  private static int counter;

  @Autowired private MockMvc mvc;
  @Autowired private ConceptSchemeService conceptSchemeService;
  @Autowired private CodelistService codelistService;
  @Autowired private DataStructureDefinitionService dsdService;
  @Autowired private DataflowService dataflowService;
  @Autowired private ObservationService observationService;
  @Autowired private AnnotationService annotationService;
  @Autowired private AppUserService appUserService;
  @Autowired private ApiTokenService apiTokenService;

  private final RequestPostProcessor dm = principal("dm@x", AppUserRole.DATENMANAGER);
  private final RequestPostProcessor oekonom = principal("oe@x", AppUserRole.OEKONOM);

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

  private record Cube(Dataflow dataflow, Long codeB02) {}

  /**
   * A small KRED-like cube: BANK (B1, B2) x ITEM (01..03) x COL (A, B), periods 2026-Q1 and
   * 2026-Q2, published. Value = bank number * 100 + item * 10 + column (A=1, B=2) + quarter / 10.
   */
  private Cube cube() {
    String suffix = "TBL" + (++counter) + "_" + System.nanoTime() % 100000;
    return asDatenmanager(
        () -> {
          ConceptScheme cs =
              conceptSchemeService.createScheme("TST", "CS_" + suffix, "1.0.0", "Concepts");
          for (String c : List.of("BANK", "ITEM", "COL", "TIME_PERIOD", "OBS_VALUE")) {
            cs = conceptSchemeService.addConcept(cs.id(), c, c.equals("ITEM") ? "Position" : c);
          }
          Codelist banks = codelistService.createCodelist("TST", "CLB_" + suffix, "1.0.0", "Banks");
          banks = codelistService.addCode(banks.id(), "B1", "First bank");
          banks = codelistService.addCode(banks.id(), "B2", "Second bank");
          Codelist items = codelistService.createCodelist("TST", "CLI_" + suffix, "1.0.0", "Items");
          items = codelistService.addCode(items.id(), "01", "Mortgages");
          items = codelistService.addCode(items.id(), "02", "Consumer loans");
          items = codelistService.addCode(items.id(), "03", "Other");
          Codelist cols = codelistService.createCodelist("TST", "CLC_" + suffix, "1.0.0", "Cols");
          cols = codelistService.addCode(cols.id(), "A", "Limits");
          cols = codelistService.addCode(cols.id(), "B", "Used");
          DataStructureDefinition dsd =
              dsdService.createDsd("TST", "DSD_" + suffix, "1.0.0", "DSD");
          dsd = dsdService.addDimension(dsd.id(), id(cs, "BANK"), banks.id(), DataType.STRING);
          dsd = dsdService.addDimension(dsd.id(), id(cs, "ITEM"), items.id(), DataType.STRING);
          dsd = dsdService.addDimension(dsd.id(), id(cs, "COL"), cols.id(), DataType.STRING);
          dsd = dsdService.addDimension(dsd.id(), id(cs, "TIME_PERIOD"), null, DataType.DATE);
          dsd = dsdService.addMeasure(dsd.id(), id(cs, "OBS_VALUE"), DataType.NUMBER);
          Dataflow df =
              dataflowService.createDataflow("TST", "DF_" + suffix, "1.0.0", "Credits", dsd.id());
          for (int b = 1; b <= 2; b++) {
            for (int i = 1; i <= 3; i++) {
              for (String col : List.of("A", "B")) {
                for (int q = 1; q <= 2; q++) {
                  observationService.recordObservationByConceptCode(
                      df.id(),
                      Map.of(
                          "BANK",
                          "B" + b,
                          "ITEM",
                          "0" + i,
                          "COL",
                          col,
                          "TIME_PERIOD",
                          "2026-Q" + q),
                      Map.of(
                          "OBS_VALUE",
                          String.valueOf(b * 100 + i * 10 + (col.equals("A") ? 1 : 2) + q / 10.0)),
                      "dm");
                }
              }
            }
          }
          observationService.promoteToBeta(df.id(), "dm");
          Long b02 =
              items.codes().stream()
                  .filter(c -> c.code().equals("02"))
                  .findFirst()
                  .orElseThrow()
                  .id();
          return new Cube(df, b02);
        });
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
  void annotationsAreMaintainedByDatenmanagerAndPublishedWithTheStructure() throws Exception {
    Cube c = cube();
    Long df = c.dataflow().id();
    String back = "/structures/dataflows/" + df;

    mvc.perform(
            post("/structures/annotations")
                .param("target", "DATAFLOW")
                .param("targetId", df.toString())
                .param("annotationType", "LAYOUT_ROW")
                .param("title", "ITEM")
                .param("back", back)
                .with(oekonom)
                .with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/structures/annotations")
                .param("target", "DATAFLOW")
                .param("targetId", df.toString())
                .param("annotationType", "LAYOUT_ROW")
                .param("title", "ITEM")
                .param("text", "Rows <of> the form")
                .param("back", "https://evil.example/")
                .with(dm)
                .with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            r -> assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/structures/dataflows"));
    assertThat(body(get(back).with(dm))).contains("LAYOUT_ROW").contains("Add annotation");
    assertThat(body(get(back).with(oekonom)))
        .contains("LAYOUT_ROW")
        .doesNotContain("Add annotation");
    // empty annotations are refused
    mvc.perform(
            post("/structures/annotations")
                .param("target", "DATAFLOW")
                .param("targetId", df.toString())
                .param("back", back)
                .with(dm)
                .with(csrf()))
        .andExpect(
            r ->
                assertThat(r.getFlashMap().get("annotationError").toString())
                    .contains("needs a type"));
    asDatenmanager(
        () ->
            annotationService.add(
                AnnotationTarget.CODE, c.codeB02(), null, "ORDER", null, "en:1", null, "dm"));

    AppUser reader =
        asDatenmanager(
            () ->
                appUserService.createUser(
                    "Eco",
                    "Nomist",
                    "oe-tbl" + counter + "-" + System.nanoTime() + "@x",
                    "secret123",
                    AppUserRole.OEKONOM));
    String token = apiTokenService.create(reader.id(), "test", 30).plainToken();
    for (String path :
        List.of(
            "/rest/dataflow/TST/" + c.dataflow().artefactId() + "?references=all",
            "/rest/structure/dataflow/TST/" + c.dataflow().artefactId() + "?references=all")) {
      String xml = body(get(path).header("Authorization", "Bearer " + token));
      assertThat(xml)
          .contains(
              "<com:Annotations><com:Annotation><com:AnnotationTitle>ITEM</com:AnnotationTitle>"
                  + "<com:AnnotationType>LAYOUT_ROW</com:AnnotationType>"
                  + "<com:AnnotationText xml:lang=\"en\">Rows &lt;of&gt; the form</com:AnnotationText>"
                  + "</com:Annotation></com:Annotations><com:Name")
          .contains("<com:AnnotationType>ORDER</com:AnnotationType>");
    }
  }

  @Test
  void theTableFollowsTheAnnotationsAndCanBeRearranged() throws Exception {
    Cube c = cube();
    Long df = c.dataflow().id();
    String base = "/explore/dataflows/" + df;
    asDatenmanager(
        () -> {
          annotationService.add(
              AnnotationTarget.DATAFLOW, df, null, "LAYOUT_ROW", "ITEM", null, null, "dm");
          annotationService.add(
              AnnotationTarget.DATAFLOW, df, null, "LAYOUT_COLUMN", "COL", null, null, "dm");
          annotationService.add(
              AnnotationTarget.DATAFLOW, df, null, "DEFAULT", "BANK=B2", null, null, "dm");
          // code 02 first
          annotationService.add(
              AnnotationTarget.CODE, c.codeB02(), null, "ORDER", null, "en:1", null, "dm");
          return null;
        });

    // economists see the published table: bank B2 (default), latest period, items x columns
    String page = body(get(base).param("view", "table").with(oekonom));
    assertThat(page)
        .contains("from the dataflow's annotations")
        .contains("6 value(s)")
        .contains(">221.2<") // B2, item 02, column A, 2026-Q2
        .contains(">232.2<")
        .doesNotContain(">121.2<") // bank B1 is not selected
        .doesNotContain(">221.1<"); // 2026-Q1 is not the latest period
    assertThat(page.indexOf("Consumer loans")).isLessThan(page.indexOf("Mortgages"));

    // another bank and period through the filters
    assertThat(
            body(
                get(base)
                    .param("view", "table")
                    .param("k_BANK", "B1")
                    .param("time", "2026-Q1")
                    .with(oekonom)))
        .contains(">111.1<")
        .doesNotContain(">221.2<");

    // rearranged: time in the columns, banks as row sections, columns become a filter
    String rearranged =
        body(
            get(base)
                .param("view", "table")
                .param("role_BANK", "SECTION")
                .param("role_ITEM", "ROW")
                .param("role_COL", "FILTER")
                .param("role_TIME_PERIOD", "COLUMN")
                .with(oekonom));
    assertThat(rearranged)
        .contains("arranged by you")
        .contains("B1 - First bank")
        .contains("B2 - Second bank")
        .contains(">2026-Q1<")
        .contains(">111.1<")
        .contains(">211.2<");

    // export of the table as shown
    String csv =
        body(get(base + "/table.csv").param("k_BANK", "B2").param("time", "2026-Q2").with(oekonom));
    assertThat(csv.lines().toList())
        .contains("02,Consumer loans,221.2,222.2")
        .contains("01,Mortgages,211.2,212.2");
  }

  @Test
  void withoutAnnotationsTheHeuristicBuildsATable() throws Exception {
    Cube c = cube();
    String page =
        body(get("/explore/dataflows/" + c.dataflow().id()).param("view", "table").with(dm));
    assertThat(page)
        .contains("chosen automatically")
        // ITEM has the most codes: rows; then BANK or COL (two codes each) in the columns
        .contains("Mortgages")
        .contains("value(s)");
  }

  @Test
  void breadcrumbAndWayBackFromASeriesToItsTable() throws Exception {
    Cube c = cube();
    Long df = c.dataflow().id();
    String base = "/explore/dataflows/" + df;
    asDatenmanager(
        () ->
            annotationService.add(
                AnnotationTarget.DATAFLOW, df, null, "LAYOUT_ROW", "ITEM", null, null, "dm"));

    // with a layout annotation the dataflow opens as table; the breadcrumb ends with the dataflow
    String page = body(get(base).param("k_BANK", "B1").with(oekonom));
    assertThat(page)
        .contains("from the dataflow's annotations")
        .contains("aria-label=\"Breadcrumb\"")
        .contains("Explore time series")
        .contains(">" + c.dataflow().artefactId() + "</span>");
    // the cells link to their series with the way back to this table
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("href=\"(/explore/dataflows/" + df + "/series/[^\"]+)\"")
            .matcher(page);
    assertThat(m.find()).isTrue();
    String series = m.group(1).replace("&amp;", "&");
    assertThat(series).contains("ret=");
    String seriesPage = body(get(java.net.URI.create(series)).with(oekonom));
    assertThat(seriesPage)
        .contains("Table (B1 · A)")
        .contains("Back to the table")
        .contains("view=table");
    // only a table of the same dataflow is accepted as way back
    assertThat(ExploreSeriesController.tableReturn(df, "https://evil.example/")).isNull();
    assertThat(
            ExploreSeriesController.tableReturn(
                df, "/explore/dataflows/" + (df + 1) + "?view=table"))
        .isNull();
    assertThat(ExploreSeriesController.tableReturn(df, base + "?view=table&k_BANK=B1")).isNotNull();
  }
}
