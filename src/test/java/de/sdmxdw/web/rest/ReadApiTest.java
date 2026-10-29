package de.sdmxdw.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.sdmxdw.AbstractIntegrationTest;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.microdata.FacetSlot;
import de.sdmxdw.microdata.FieldDataType;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.security.ApiTokenService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end tests of the read API through the real security filter chain: personal API tokens,
 * Beta/Alpha visibility per role, SDMX structure and data queries and the microdata API.
 */
@AutoConfigureMockMvc
class ReadApiTest extends AbstractIntegrationTest {

  private static int counter;

  @Autowired private MockMvc mvc;
  @Autowired private AppUserService appUserService;
  @Autowired private ApiTokenService apiTokenService;
  @Autowired private ConceptSchemeService conceptSchemeService;
  @Autowired private CodelistService codelistService;
  @Autowired private DataStructureDefinitionService dsdService;
  @Autowired private DataflowService dataflowService;
  @Autowired private ObservationService observationService;
  @Autowired private MicrodataFlowService microdataFlowService;
  @Autowired private MicrodataRecordService microdataRecordService;

  private String suffix;
  private String dmToken;
  private String oekonomToken;

  @BeforeEach
  void setUpUsers() {
    suffix = "API" + (++counter) + "_" + System.nanoTime() % 100000;
    AppUser dm =
        asDatenmanager(
            () ->
                appUserService.createUser(
                    "Data",
                    "Manager",
                    "dm-" + suffix + "@x",
                    "secret123",
                    AppUserRole.DATENMANAGER));
    AppUser oekonom =
        asDatenmanager(
            () ->
                appUserService.createUser(
                    "Eco", "Nomist", "oe-" + suffix + "@x", "secret123", AppUserRole.OEKONOM));
    dmToken = apiTokenService.create(dm.id(), "test", 30).plainToken();
    oekonomToken = apiTokenService.create(oekonom.id(), "test", 30).plainToken();
  }

  // --- authentication ----------------------------------------------------------------------------

  @Test
  void requiresValidTokenAndAnswers401InsteadOfRedirect() throws Exception {
    mvc.perform(get("/rest/dataflow"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("WWW-Authenticate"));
    mvc.perform(get("/rest/dataflow").header("Authorization", "Bearer sdw_unknown"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/rest/dataflow").header("Authorization", "Bearer " + oekonomToken))
        .andExpect(status().isOk());
    // token as basic-auth password (any user name), e.g. for clients without header support
    String basic =
        java.util.Base64.getEncoder()
            .encodeToString(("me:" + oekonomToken).getBytes(StandardCharsets.UTF_8));
    mvc.perform(get("/rest/dataflow").header("Authorization", "Basic " + basic))
        .andExpect(status().isOk());
  }

  @Test
  void revokedAndExpiredTokensAreRejected() throws Exception {
    AppUser user =
        asDatenmanager(
            () ->
                appUserService.createUser(
                    "Re", "Voked", "rv-" + suffix + "@x", "secret123", AppUserRole.OEKONOM));
    var created = apiTokenService.create(user.id(), "to revoke", null);
    mvc.perform(get("/rest/dataflow").header("Authorization", "Bearer " + created.plainToken()))
        .andExpect(status().isOk());
    assertThat(apiTokenService.revoke(user.id(), created.token().id())).isTrue();
    mvc.perform(get("/rest/dataflow").header("Authorization", "Bearer " + created.plainToken()))
        .andExpect(status().isUnauthorized());
    assertThat(apiTokenService.findByUser(user.id()).getFirst().revokedAt()).isNotNull();
  }

  // --- time series -------------------------------------------------------------------------------

  @Test
  void alphaDataStaysInvisibleForOekonomTokens() throws Exception {
    Dataflow df = dataflowWithData();
    // an unpublished Alpha correction on top of the published Beta value
    asDatenmanager(() -> record(df, "A", "2024", "999"));

    assertThat(body(api("/rest/data/" + flowRef(df) + "?format=csv", oekonomToken)))
        .contains("A,2024,100.5")
        .doesNotContain("999");
    mvc.perform(api("/rest/data/" + flowRef(df) + "?format=csv&stage=ALPHA", oekonomToken))
        .andExpect(status().isForbidden());
    assertThat(body(api("/rest/data/" + flowRef(df) + "?format=csv&stage=ALPHA", dmToken)))
        .contains("A,2024,999");
  }

  @Test
  void structureQueriesResolveReferencesInBothSdmxVersions() throws Exception {
    Dataflow df = dataflowWithData();
    String v21 =
        body(api("/rest/dataflow/TST/DF_" + suffix + "/latest?references=all", oekonomToken));
    assertThat(v21)
        .contains("schemas/v2_1/structure")
        .contains("<str:Dataflow id=\"DF_" + suffix + "\"")
        .contains("<str:DataStructure id=\"DSD_" + suffix + "\"")
        .contains("<str:Codelist id=\"CL_" + suffix + "\"")
        .contains("<str:ConceptScheme id=\"CS_" + suffix + "\"")
        .contains("<str:PrimaryMeasure id=\"OBS_VALUE\"")
        // the second measure becomes an observation attribute under 2.1
        .contains("<str:Attribute id=\"OBS_STATUS_NUM\"");

    // instance agency alias: sdmx1 sends its source id "SDMXDW" as agency
    assertThat(body(api("/rest/dataflow/SDMXDW/DF_" + suffix, oekonomToken)))
        .contains("agencyID=\"TST\"");

    String v30 = body(api("/rest/structure/datastructure/TST/DSD_" + suffix + "/~", oekonomToken));
    assertThat(v30)
        .contains("schemas/v3_0/structure")
        .contains("<str:Measure id=\"OBS_STATUS_NUM\"")
        .contains(
            "<str:Enumeration>urn:sdmx:org.sdmx.infomodel.codelist.Codelist=TST:CL_" + suffix);

    mvc.perform(api("/rest/codelist/TST/CL_DOES_NOT_EXIST", oekonomToken))
        .andExpect(status().isNotFound());
    assertThat(df.id()).isNotNull();
  }

  @Test
  void dataFormatsFollowSdmxConventions() throws Exception {
    Dataflow df = dataflowWithData();
    String path = "/rest/data/" + flowRef(df) + "/A";

    assertThat(body(api(path, oekonomToken)))
        .contains("<message:GenericData")
        .contains("<generic:ObsValue value=\"100.5\"/>")
        .contains("<generic:Value id=\"OBS_STATUS_NUM\" value=\"1\"/>");
    assertThat(
            body(
                api(path, oekonomToken)
                    .header(
                        "Accept", "application/vnd.sdmx.structurespecificdata+xml;version=2.1")))
        .contains("<message:StructureSpecificData")
        .contains("TIME_PERIOD=\"2024\" OBS_VALUE=\"100.5\" OBS_STATUS_NUM=\"1\"");
    assertThat(body(api(path + "?format=xml&sdmxVersion=3.0", oekonomToken)))
        .contains("schemas/v3_0/message")
        .contains("OBS_VALUE=\"100.5\" OBS_STATUS_NUM=\"1\"");
    assertThat(body(api(path + "?format=csv", oekonomToken)))
        .startsWith("STRUCTURE,STRUCTURE_ID,ACTION,FREQ,TIME_PERIOD,OBS_VALUE,OBS_STATUS_NUM\n");
    assertThat(
            body(
                api(path, oekonomToken)
                    .header("Accept", "application/vnd.sdmx.data+csv;version=1.0.0")))
        .startsWith("DATAFLOW,FREQ,TIME_PERIOD,OBS_VALUE,OBS_STATUS_NUM\n")
        .contains("TST:DF_" + suffix + "(1.0.0),A,2024,100.5,1");
    assertThat(body(api(path + "?format=json", oekonomToken)))
        .contains("\"observations\":{\"0\":[100.5,0],\"1\":[101.5,0]}")
        .contains("\"role\":\"time\"");
    assertThat(
            body(
                api(
                    "/rest/data/dataflow/TST/DF_"
                        + suffix
                        + "/1.0.0/A?format=csv&lastNObservations=1",
                    oekonomToken)))
        .contains("A,2025,")
        .doesNotContain("A,2024,");
    assertThat(
            body(
                api(
                    "/rest/data/dataflow/TST/DF_"
                        + suffix
                        + "/1.0.0?format=csv&c[TIME_PERIOD]=le:2024",
                    oekonomToken)))
        .contains("A,2024,")
        .doesNotContain("A,2025,");

    mvc.perform(api("/rest/data/" + flowRef(df) + "/A.B", oekonomToken))
        .andExpect(status().isBadRequest());
    mvc.perform(api("/rest/data/" + flowRef(df) + "/Z", oekonomToken))
        .andExpect(status().isNotFound());
  }

  // --- microdata ---------------------------------------------------------------------------------

  @Test
  void microdataApiDeliversMainAndGroupTablesWithStructure() throws Exception {
    MicrodataFlow target =
        asDatenmanager(
            () ->
                microdataFlowService.createFlow(
                    "TST", "MDF_CP_" + suffix, "1.0.0", "Counterparty", null));
    MicrodataFlow flow = microdataFlowWithData(target);
    String ref = "TST,MDF_LOAN_" + suffix + ",1.0.0";

    String structure = body(api("/rest/microdata/" + ref + "/structure", oekonomToken));
    assertThat(structure)
        .contains("\"reportingPeriods\":[\"2026-Q1\"]")
        .contains("\"references\":{")
        .contains("TST,MDF_CP_" + suffix + ",1.0.0")
        .contains("\"code\":\"COLLATERAL\"")
        .contains("\"primaryKey\":[\"ENTITY_ID\",\"REPORTING_PERIOD\",\"ITEM_NO\"]");

    String csv = body(api("/rest/microdata/" + ref + "?reportingPeriod=latest", oekonomToken));
    assertThat(csv)
        .startsWith(
            "STRUCTURE,STRUCTURE_ID,ACTION,ENTITY_ID,REPORTING_PERIOD,CURRENCY,AMOUNT,COUNTERPARTY\n")
        .contains("L-1,2026-Q1,CHF,1000,CP-1")
        .contains("L-2,2026-Q1,EUR,2000,CP-1")
        // Alpha-only record stays invisible
        .doesNotContain("L-3");

    assertThat(body(api("/rest/microdata/" + ref + "?c[CURRENCY]=EUR", oekonomToken)))
        .contains("L-2")
        .doesNotContain("L-1,");
    assertThat(
            body(
                api(
                    "/rest/microdata/" + ref + "?c[COUNTERPARTY]=CP-1&c[AMOUNT]=1000",
                    oekonomToken)))
        .contains("L-1,")
        .doesNotContain("L-2");

    String groups = body(api("/rest/microdata/" + ref + "/groups/COLLATERAL", oekonomToken));
    assertThat(groups)
        .contains("ENTITY_ID,REPORTING_PERIOD,ITEM_NO,COLLATERAL_TYPE,COLLATERAL_VALUE")
        .contains("L-1,2026-Q1,1,REAL_ESTATE,800")
        .contains("L-1,2026-Q1,2,SECURITIES,150");

    mvc.perform(api("/rest/microdata/" + ref + "?format=json&limit=1", oekonomToken))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(header().string("Link", org.hamcrest.Matchers.containsString("offset=1")));
    assertThat(body(api("/rest/microdata/" + ref + "?format=json", oekonomToken)))
        .contains("\"AMOUNT\":1000");

    byte[] parquet =
        mvc.perform(api("/rest/microdata/" + ref + "?format=parquet", oekonomToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(new String(parquet, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");

    mvc.perform(api("/rest/microdata/" + ref + "?stage=ALPHA", oekonomToken))
        .andExpect(status().isForbidden());
    assertThat(body(api("/rest/microdata/" + ref + "?stage=ALPHA", dmToken))).contains("L-3");
    assertThat(flow.id()).isNotNull();
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private MicrodataFlow microdataFlowWithData(MicrodataFlow target) {
    return asDatenmanager(
        () -> {
          MicrodataFlow flow =
              microdataFlowService.createFlow("TST", "MDF_LOAN_" + suffix, "1.0.0", "Loans", null);
          microdataFlowService.addField(
              flow.id(),
              "CURRENCY",
              "Currency",
              FieldDataType.STRING,
              null,
              true,
              FacetSlot.facet1);
          microdataFlowService.addField(
              flow.id(), "AMOUNT", "Amount", FieldDataType.NUMBER, null, true, null);
          microdataFlowService.addReferenceField(
              flow.id(), "COUNTERPARTY", "Counterparty", target.id(), true, null);
          microdataFlowService.addGroupField(
              flow.id(),
              "COLLATERAL",
              "COLLATERAL_TYPE",
              "Type",
              FieldDataType.STRING,
              null,
              false);
          microdataFlowService.addGroupField(
              flow.id(),
              "COLLATERAL",
              "COLLATERAL_VALUE",
              "Value",
              FieldDataType.NUMBER,
              null,
              false);
          microdataRecordService.ingestRecord(target.id(), "CP-1", "2026-Q1", Map.of(), "dm@x");
          microdataRecordService.ingestRecord(
              flow.id(),
              "L-1",
              "2026-Q1",
              Map.of("CURRENCY", "CHF", "AMOUNT", "1000", "COUNTERPARTY", "CP-1"),
              Map.of(
                  "COLLATERAL",
                  List.of(
                      Map.of("COLLATERAL_TYPE", "REAL_ESTATE", "COLLATERAL_VALUE", "800"),
                      Map.of("COLLATERAL_TYPE", "SECURITIES", "COLLATERAL_VALUE", "150"))),
              "dm@x");
          microdataRecordService.ingestRecord(
              flow.id(),
              "L-2",
              "2026-Q1",
              Map.of("CURRENCY", "EUR", "AMOUNT", "2000", "COUNTERPARTY", "CP-1"),
              "dm@x");
          microdataRecordService.promoteToBeta(target.id(), "dm@x");
          microdataRecordService.promoteToBeta(flow.id(), "dm@x");
          microdataRecordService.ingestRecord(
              flow.id(),
              "L-3",
              "2026-Q1",
              Map.of("CURRENCY", "CHF", "AMOUNT", "5", "COUNTERPARTY", "CP-1"),
              "dm@x");
          return flow;
        });
  }

  /** FREQ x TIME_PERIOD with two measures, Beta values for 2024 and 2025. */
  private Dataflow dataflowWithData() {
    return asDatenmanager(
        () -> {
          ConceptScheme cs =
              conceptSchemeService.createScheme("TST", "CS_" + suffix, "1.0.0", "Concepts");
          cs = conceptSchemeService.addConcept(cs.id(), "FREQ", "Frequency");
          cs = conceptSchemeService.addConcept(cs.id(), "TIME_PERIOD", "Time period");
          cs = conceptSchemeService.addConcept(cs.id(), "OBS_VALUE", "Observation value");
          cs = conceptSchemeService.addConcept(cs.id(), "OBS_STATUS_NUM", "Status");
          Codelist cl = codelistService.createCodelist("TST", "CL_" + suffix, "1.0.0", "Freq");
          cl = codelistService.addCode(cl.id(), "A", "Annual");
          DataStructureDefinition dsd =
              dsdService.createDsd("TST", "DSD_" + suffix, "1.0.0", "DSD");
          dsd = dsdService.addDimension(dsd.id(), conceptId(cs, "FREQ"), cl.id(), DataType.STRING);
          dsd =
              dsdService.addDimension(dsd.id(), conceptId(cs, "TIME_PERIOD"), null, DataType.DATE);
          dsd = dsdService.addMeasure(dsd.id(), conceptId(cs, "OBS_VALUE"), DataType.NUMBER);
          dsd = dsdService.addMeasure(dsd.id(), conceptId(cs, "OBS_STATUS_NUM"), DataType.NUMBER);
          Dataflow df =
              dataflowService.createDataflow("TST", "DF_" + suffix, "1.0.0", "Flow", dsd.id());
          record(df, "A", "2024", "100.5");
          record(df, "A", "2025", "101.5");
          observationService.promoteToBeta(df.id(), "dm@x");
          return df;
        });
  }

  private Void record(Dataflow df, String freq, String period, String value) {
    DataStructureDefinition dsd = dsdService.findById(df.dsdId());
    Map<Long, String> dims = new HashMap<>();
    dsd.dimensions().forEach(d -> dims.put(d.id(), d.dataType() == DataType.DATE ? period : freq));
    Map<Long, String> measures = new HashMap<>();
    // first measure (lowest id) = value, second = status 1
    dsd.measures().stream()
        .sorted(java.util.Comparator.comparing(de.sdmxdw.structure.dsd.Measure::id))
        .forEach(m -> measures.put(m.id(), measures.isEmpty() ? value : "1"));
    observationService.recordObservation(df.id(), dims, measures, "dm@x");
    return null;
  }

  private static Long conceptId(ConceptScheme cs, String code) {
    return cs.concepts().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().id();
  }

  private static String flowRef(Dataflow df) {
    return df.agencyId() + "," + df.artefactId() + "," + df.version();
  }

  private static MockHttpServletRequestBuilder api(String url, String token) {
    return get(url).header("Authorization", "Bearer " + token);
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  /** Runs set-up code as Datenmanager without leaking that identity into the MockMvc requests. */
  private static <T> T asDatenmanager(Supplier<T> action) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "test-setup", null, List.of(new SimpleGrantedAuthority("ROLE_DATENMANAGER"))));
    try {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
      TestSecurityContextHolder.clearContext();
    }
  }
}
