package de.sdmxdw.demo;

import de.sdmxdw.data.BulkLoadResult;
import de.sdmxdw.data.observation.ObservationBulkLoader;
import de.sdmxdw.data.observation.ObservationService;
import de.sdmxdw.microdata.FacetSlot;
import de.sdmxdw.microdata.FieldDataType;
import de.sdmxdw.microdata.FieldDefinition;
import de.sdmxdw.microdata.MicrodataCategorisationService;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.microdata.MicrodataPayload;
import de.sdmxdw.microdata.MicrodataRecordService;
import de.sdmxdw.microdata.MicrodataStage;
import de.sdmxdw.structure.categorisation.CategorisationService;
import de.sdmxdw.structure.category.Category;
import de.sdmxdw.structure.category.CategoryScheme;
import de.sdmxdw.structure.category.CategorySchemeService;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Optional demo data (SDMXDW_DEMO_DATA=true): a small, entirely fictional example under the agency
 * DEMO, so that a fresh installation is not empty. It shows the main features without referring to
 * any real statistics:
 *
 * <ul>
 *   <li>time series: loans outstanding by region and sector, quarterly 2020-Q1 to 2026-Q2,
 *       published, with revisions and one pending correction in Alpha,
 *   <li>microdata: a loan register with borrowers and loans (reference, codes, repeating group of
 *       collaterals) for two reporting periods, documented field by field,
 *   <li>category schemes with Markdown descriptions for both explorers.
 * </ul>
 *
 * Created once: nothing happens when the dataflow DEMO:DF_DEMO_LOANS already exists. Values are
 * generated with a fixed random seed, so every installation gets the same data.
 */
@Component
@Order(20)
public class DemoDataSeeder implements ApplicationRunner {

  static final String AGENCY = "DEMO";
  static final String VERSION = "1.0.0";
  static final String USER = "demo-data";
  static final List<String> REGIONS = List.of("NORTH", "SOUTH", "EAST", "WEST");
  static final List<String> SECTORS = List.of("HH", "NFC", "GOV");

  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final boolean enabled;
  private final ConceptSchemeService conceptSchemes;
  private final CodelistService codelists;
  private final DataStructureDefinitionService dsds;
  private final DataflowService dataflows;
  private final CategorySchemeService categorySchemes;
  private final CategorisationService categorisations;
  private final ObservationBulkLoader observationLoader;
  private final ObservationService observations;
  private final MicrodataFlowService microdataFlows;
  private final MicrodataCategorisationService microdataCategorisations;
  private final MicrodataRecordService records;

  public DemoDataSeeder(
      @Value("${sdmxdw.demo-data.enabled:false}") boolean enabled,
      ConceptSchemeService conceptSchemes,
      CodelistService codelists,
      DataStructureDefinitionService dsds,
      DataflowService dataflows,
      CategorySchemeService categorySchemes,
      CategorisationService categorisations,
      ObservationBulkLoader observationLoader,
      ObservationService observations,
      MicrodataFlowService microdataFlows,
      MicrodataCategorisationService microdataCategorisations,
      MicrodataRecordService records) {
    this.enabled = enabled;
    this.conceptSchemes = conceptSchemes;
    this.codelists = codelists;
    this.dsds = dsds;
    this.dataflows = dataflows;
    this.categorySchemes = categorySchemes;
    this.categorisations = categorisations;
    this.observationLoader = observationLoader;
    this.observations = observations;
    this.microdataFlows = microdataFlows;
    this.microdataCategorisations = microdataCategorisations;
    this.records = records;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (enabled && seedIfEmpty()) {
      log.info("Demo data created (agency {})", AGENCY);
    }
  }

  /** Creates the demo data unless it exists; true if it was created. */
  public boolean seedIfEmpty() {
    return asSystem(
        () -> {
          boolean exists =
              dataflows.findAll().stream()
                  .anyMatch(
                      d -> d.agencyId().equals(AGENCY) && d.artefactId().equals("DF_DEMO_LOANS"));
          if (exists) {
            return false;
          }
          Codes codes = codelists();
          timeSeries(codes);
          microdata(codes);
          return true;
        });
  }

  // --- codelists -------------------------------------------------------------------------------

  record Codes(
      Codelist freq,
      Codelist region,
      Codelist sector,
      Codelist currency,
      Codelist loanType,
      Codelist collateral) {}

  private Codes codelists() {
    Codelist freq = codelist("CL_DEMO_FREQ", "Frequency", new String[][] {{"Q", "Quarterly"}});
    Codelist region =
        codelist(
            "CL_DEMO_REGION",
            "Region (fictional)",
            new String[][] {
              {"NORTH", "Region North"},
              {"SOUTH", "Region South"},
              {"EAST", "Region East"},
              {"WEST", "Region West"}
            });
    Codelist sector =
        codelist(
            "CL_DEMO_SECTOR",
            "Borrower sector",
            new String[][] {
              {"HH", "Households", "Private persons and households"},
              {"NFC", "Non-financial corporations", "Companies outside the financial sector"},
              {"GOV", "General government", "Public administration at all levels"}
            });
    Codelist currency =
        codelist(
            "CL_DEMO_CURRENCY",
            "Currency",
            new String[][] {{"CHF", "Swiss franc"}, {"EUR", "Euro"}, {"USD", "US dollar"}});
    Codelist loanType =
        codelist(
            "CL_DEMO_LOAN_TYPE",
            "Loan type",
            new String[][] {
              {"MORT", "Mortgage", "Loan secured by real estate"},
              {"CONS", "Consumer loan", "Unsecured loan to a household"},
              {"CORP", "Corporate loan", "Term loan to a company or public body"},
              {"LINE", "Credit line", "Revolving facility; the amount is the drawn part"}
            });
    Codelist collateral =
        codelist(
            "CL_DEMO_COLLATERAL",
            "Collateral type",
            new String[][] {
              {"RE", "Real estate"},
              {"SEC", "Securities"},
              {"GUA", "Guarantee"},
              {"CASH", "Cash deposit"}
            });
    return new Codes(freq, region, sector, currency, loanType, collateral);
  }

  private Codelist codelist(String id, String name, String[][] entries) {
    Codelist cl = codelists.createCodelist(AGENCY, id, VERSION, name);
    for (String[] e : entries) {
      cl = codelists.addCode(cl.id(), e[0], e[1], e.length > 2 ? e[2] : null);
    }
    return cl;
  }

  // --- time series -----------------------------------------------------------------------------

  private void timeSeries(Codes codes) {
    ConceptScheme cs =
        conceptSchemes.createScheme(AGENCY, "CS_DEMO_CONCEPTS", VERSION, "Demo concepts");
    for (String[] c :
        new String[][] {
          {"FREQ", "Frequency"},
          {"REGION", "Region"},
          {"SECTOR", "Borrower sector"},
          {"TIME_PERIOD", "Time period"},
          {"OBS_VALUE", "Loans outstanding (millions)"}
        }) {
      cs = conceptSchemes.addConcept(cs.id(), c[0], c[1]);
    }
    DataStructureDefinition dsd =
        dsds.createDsd(AGENCY, "DSD_DEMO_LOANS", VERSION, "Loans outstanding (demo)");
    dsd = dsds.addDimension(dsd.id(), concept(cs, "FREQ"), codes.freq().id(), DataType.STRING);
    dsd = dsds.addDimension(dsd.id(), concept(cs, "REGION"), codes.region().id(), DataType.STRING);
    dsd = dsds.addDimension(dsd.id(), concept(cs, "SECTOR"), codes.sector().id(), DataType.STRING);
    dsd = dsds.addDimension(dsd.id(), concept(cs, "TIME_PERIOD"), null, DataType.DATE);
    dsd = dsds.addMeasure(dsd.id(), concept(cs, "OBS_VALUE"), DataType.NUMBER);
    Dataflow df =
        dataflows.createDataflow(
            AGENCY, "DF_DEMO_LOANS", VERSION, "Loans outstanding by region and sector", dsd.id());

    CategoryScheme topics =
        categorySchemes.createScheme(AGENCY, "CS_DEMO_TOPICS", VERSION, "Demo statistics");
    topics = categorySchemes.addCategory(topics.id(), null, "LOANS", "Lending");
    categorySchemes.updateDescription(
        topics.id(),
        """
        # Demo statistics

        **Fictional data** for trying out SDMxDW - not real statistics.

        - **Loans outstanding** by region and borrower sector, quarterly since 2020, in millions.
        - The last quarter was revised once; one correction is still unpublished (visible to \
        data managers under *Pending changes*).
        """);
    categorisations.assignDataflowToCategory(df.id(), category(topics, "LOANS"));

    // first release of all quarters, published
    Random random = new Random(42);
    List<ObservationBulkLoader.Row> rows = new ArrayList<>();
    for (String region : REGIONS) {
      for (String sector : SECTORS) {
        double level =
            (sector.equals("HH") ? 800 : sector.equals("NFC") ? 500 : 150)
                * (1 + REGIONS.indexOf(region) * 0.15);
        double growth = 0.004 + random.nextDouble() * 0.008;
        for (int year = 2020; year <= 2026; year++) {
          for (int quarter = 1; quarter <= 4; quarter++) {
            if (year == 2026 && quarter > 2) {
              break;
            }
            level *= 1 + growth + random.nextGaussian() * 0.006;
            rows.add(row(rows.size(), region, sector, year + "-Q" + quarter, level));
          }
        }
      }
    }
    check(observationLoader.load(df.id(), rows, USER));
    observations.promoteToBeta(df.id(), USER);

    // revision of the last quarter for two series, published; one more correction stays in Alpha
    check(
        observationLoader.load(
            df.id(),
            List.of(revised(rows, "NORTH", "HH", 1.012), revised(rows, "SOUTH", "NFC", 0.985)),
            USER));
    observations.promoteToBeta(df.id(), USER);
    check(observationLoader.load(df.id(), List.of(revised(rows, "EAST", "GOV", 1.03)), USER));
  }

  private static ObservationBulkLoader.Row row(
      int n, String region, String sector, String period, double value) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("FREQ", "Q");
    values.put("REGION", region);
    values.put("SECTOR", sector);
    values.put("TIME_PERIOD", period);
    values.put("OBS_VALUE", String.format(java.util.Locale.ROOT, "%.1f", value));
    return new ObservationBulkLoader.Row("demo-data", n + 1, values);
  }

  private static ObservationBulkLoader.Row revised(
      List<ObservationBulkLoader.Row> rows, String region, String sector, double factor) {
    ObservationBulkLoader.Row last =
        rows.stream()
            .filter(
                r ->
                    r.values().get("REGION").equals(region)
                        && r.values().get("SECTOR").equals(sector)
                        && r.values().get("TIME_PERIOD").equals("2026-Q2"))
            .findFirst()
            .orElseThrow();
    double value = Double.parseDouble(last.values().get("OBS_VALUE")) * factor;
    return row(0, region, sector, "2026-Q2", value);
  }

  // --- microdata -------------------------------------------------------------------------------

  private void microdata(Codes codes) {
    CategoryScheme register =
        categorySchemes.createScheme(AGENCY, "CS_DEMO_REGISTER", VERSION, "Demo loan register");
    register = categorySchemes.addCategory(register.id(), null, "LOANS", "Loan register");
    categorySchemes.updateDescription(
        register.id(),
        """
        # Demo loan register

        **Fictional granular data** for trying out SDMxDW: borrowers and their loans with \
        collaterals, for two reporting periods.

        - *Borrowers* and *Loans* are linked: a loan references its borrower.
        - Collaterals are a repeating group on the loan.
        - Every field is documented in the *Documentation* tab of the flows.
        """);
    Long category = category(register, "LOANS");

    MicrodataFlow borrowers =
        microdataFlows.createFlow(
            AGENCY,
            "MDF_DEMO_BORROWER",
            VERSION,
            "Borrowers",
            "A borrower of the demo bank: a household, a company or a public body.\n\n"
                + "The entity id is the bank's customer number.");
    microdataCategorisations.assignFlowToCategory(borrowers.id(), category);
    document(
        microdataFlows.addField(
            borrowers.id(), "NAME", "Name", FieldDataType.STRING, null, true, null),
        "M",
        "Name of the borrower; for households a pseudonym.",
        Map.of("Example", "Example Bakery Ltd"));
    document(
        microdataFlows.addField(
            borrowers.id(),
            "SECTOR",
            "Sector",
            FieldDataType.STRING,
            codes.sector().id(),
            true,
            FacetSlot.facet1),
        "M",
        "Institutional sector of the borrower.",
        Map.of());
    document(
        microdataFlows.addField(
            borrowers.id(),
            "REGION",
            "Region",
            FieldDataType.STRING,
            codes.region().id(),
            true,
            FacetSlot.facet2),
        "M",
        "Region of the borrower's domicile.",
        Map.of());
    document(
        microdataFlows.addField(
            borrowers.id(), "EMPLOYEES", "Employees", FieldDataType.NUMBER, null, false, null),
        "O",
        "Number of employees, for companies only.",
        Map.of("Unit", "persons"));

    MicrodataFlow loans =
        microdataFlows.createFlow(
            AGENCY,
            "MDF_DEMO_LOAN",
            VERSION,
            "Loans",
            "A single loan of the demo bank to one borrower, with its collaterals.\n\n"
                + "Amounts are in the loan currency, rates as decimals (0.025 = 2.5 %).");
    microdataCategorisations.assignFlowToCategory(loans.id(), category);
    document(
        microdataFlows.addReferenceField(
            loans.id(), "BORROWER", "Borrower", borrowers.id(), true, null),
        "M",
        "The borrower of the loan (entity id of a record in *Borrowers*).",
        Map.of());
    document(
        microdataFlows.addField(
            loans.id(),
            "LOAN_TYPE",
            "Loan type",
            FieldDataType.STRING,
            codes.loanType().id(),
            true,
            FacetSlot.facet1),
        "M",
        "Kind of loan.",
        Map.of());
    document(
        microdataFlows.addField(
            loans.id(),
            "CURRENCY",
            "Currency",
            FieldDataType.STRING,
            codes.currency().id(),
            true,
            FacetSlot.facet2),
        "M",
        "Currency of the loan.",
        Map.of());
    document(
        microdataFlows.addField(
            loans.id(), "AMOUNT", "Amount outstanding", FieldDataType.NUMBER, null, true, null),
        "M",
        "Amount outstanding at the end of the reporting period, in the loan currency. "
            + "For credit lines the drawn amount.",
        Map.of("Unit", "currency units", "Example", "250000"));
    document(
        microdataFlows.addField(
            loans.id(), "INTEREST_RATE", "Interest rate", FieldDataType.NUMBER, null, true, null),
        "M",
        "Current annual interest rate as a decimal.",
        Map.of("Example", "0.0185"));
    document(
        microdataFlows.addField(
            loans.id(), "START_DATE", "Start date", FieldDataType.DATE, null, true, null),
        "M",
        "Date the loan was paid out.",
        Map.of());
    document(
        microdataFlows.addField(
            loans.id(), "MATURITY_DATE", "Maturity date", FieldDataType.DATE, null, false, null),
        "O",
        "Contractual maturity; empty for loans without a fixed term.",
        Map.of());
    document(
        microdataFlows.addField(
            loans.id(), "IMPAIRED", "Impaired", FieldDataType.BOOLEAN, null, true, null),
        "M",
        "True when the loan is impaired (payment overdue for more than 90 days).",
        Map.of());
    document(
        microdataFlows.addGroupField(
            loans.id(),
            "COLLATERAL",
            "TYPE",
            "Collateral type",
            FieldDataType.STRING,
            codes.collateral().id(),
            true),
        "M",
        "Kind of collateral.",
        Map.of());
    document(
        microdataFlows.addGroupField(
            loans.id(),
            "COLLATERAL",
            "VALUE",
            "Collateral value",
            FieldDataType.NUMBER,
            null,
            true),
        "M",
        "Market value of the collateral in the loan currency.",
        Map.of("Unit", "currency units"));

    // records: 2026-Q1 published; 2026-Q2 published; one correction pending in Alpha
    Random random = new Random(7);
    List<Borrower> people = borrowers(random);
    for (String period : List.of("2026-Q1", "2026-Q2")) {
      for (Borrower b : people) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("NAME", b.name());
        fields.put("SECTOR", b.sector());
        fields.put("REGION", b.region());
        if (b.employees() != null) {
          fields.put("EMPLOYEES", b.employees());
        }
        records.ingestRecord(borrowers.id(), b.id(), period, fields, USER);
      }
      Random loanRandom = new Random(11);
      int n = 0;
      for (Borrower b : people) {
        int count = b.sector().equals("HH") ? 1 : 2;
        for (int i = 0; i < count; i++) {
          n++;
          loan(loans, b, n, period, loanRandom);
        }
      }
      records.promoteToBeta(borrowers.id(), USER);
      records.promoteToBeta(loans.id(), USER);
    }
    // a household loan becomes impaired: corrected in Alpha, not yet published
    MicrodataPayload current =
        records.parsePayload(
            records
                .findCurrentOne(loans.id(), "L-0003", "2026-Q2", MicrodataStage.ALPHA)
                .orElseThrow()
                .payloadJson());
    Map<String, String> corrected = new LinkedHashMap<>(current.scalars());
    corrected.values().removeIf(java.util.Objects::isNull);
    corrected.put("IMPAIRED", "true");
    records.correctRecord(
        loans.id(),
        "L-0003",
        "2026-Q2",
        corrected,
        current.groups(),
        "Demo: payment overdue for more than 90 days",
        USER);
  }

  record Borrower(String id, String name, String sector, String region, String employees) {}

  private static List<Borrower> borrowers(Random random) {
    String[] companies = {
      "Example Bakery Ltd",
      "Sample Logistics Inc",
      "Demo Machines AG",
      "Fictional Hotels SA",
      "Placeholder Software GmbH"
    };
    List<Borrower> result = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      String id = String.format("B-%03d", i + 1);
      String region = REGIONS.get(i % REGIONS.size());
      if (i < 5) {
        result.add(new Borrower(id, "Household " + id, "HH", region, null));
      } else if (i < 10) {
        result.add(
            new Borrower(
                id, companies[i - 5], "NFC", region, String.valueOf(5 + random.nextInt(400))));
      } else {
        result.add(new Borrower(id, "Municipality of Demo " + (i - 9), "GOV", region, null));
      }
    }
    return result;
  }

  private void loan(MicrodataFlow loans, Borrower b, int n, String period, Random random) {
    String type =
        switch (b.sector()) {
          case "HH" -> n % 3 == 0 ? "CONS" : "MORT";
          case "NFC" -> n % 2 == 0 ? "LINE" : "CORP";
          default -> "CORP";
        };
    double base =
        switch (type) {
          case "MORT" -> 400_000 + random.nextInt(600_000);
          case "CONS" -> 10_000 + random.nextInt(30_000);
          case "LINE" -> 50_000 + random.nextInt(250_000);
          default -> 200_000 + random.nextInt(2_000_000);
        };
    // amortisation between the two periods
    long amount = Math.round((period.equals("2026-Q1") ? base : base * 0.98) / 100) * 100;
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("BORROWER", b.id());
    fields.put("LOAN_TYPE", type);
    fields.put("CURRENCY", n % 7 == 0 ? "EUR" : "CHF");
    fields.put("AMOUNT", String.valueOf(amount));
    fields.put(
        "INTEREST_RATE",
        String.format(
            java.util.Locale.ROOT,
            "%.4f",
            (type.equals("CONS") ? 0.06 : 0.012) + random.nextInt(150) / 10000.0));
    int startYear = 2015 + random.nextInt(11);
    fields.put("START_DATE", startYear + "-0" + (1 + random.nextInt(9)) + "-01");
    if (!type.equals("LINE")) {
      fields.put("MATURITY_DATE", (startYear + 5 + random.nextInt(10)) + "-12-31");
    }
    fields.put("IMPAIRED", "false");
    List<Map<String, String>> collaterals = new ArrayList<>();
    if (type.equals("MORT")) {
      collaterals.add(
          Map.of("TYPE", "RE", "VALUE", String.valueOf(Math.round(amount * 1.6 / 1000) * 1000)));
    } else if (type.equals("CORP")) {
      collaterals.add(
          Map.of("TYPE", "SEC", "VALUE", String.valueOf(Math.round(amount * 0.5 / 1000) * 1000)));
      if (n % 2 == 1) {
        collaterals.add(
            Map.of("TYPE", "GUA", "VALUE", String.valueOf(Math.round(amount * 0.3 / 1000) * 1000)));
      }
    }
    records.ingestRecord(
        loans.id(),
        String.format("L-%04d", n),
        period,
        fields,
        collaterals.isEmpty() ? Map.of() : Map.of("COLLATERAL", collaterals),
        USER);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void document(
      FieldDefinition field, String obligation, String description, Map<String, String> attrs) {
    microdataFlows.documentField(field.id(), obligation, null, null);
    microdataFlows.describeField(
        field.microdataFlowId(), field.id(), description, new LinkedHashMap<>(attrs), USER);
  }

  private static Long concept(ConceptScheme cs, String code) {
    return cs.concepts().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().id();
  }

  private static Long category(CategoryScheme cs, String code) {
    return cs.categories().stream()
        .filter(c -> c.code().equals(code))
        .map(Category::id)
        .findFirst()
        .orElseThrow();
  }

  private static void check(BulkLoadResult result) {
    if (!result.errors().isEmpty()) {
      throw new IllegalStateException("Demo data rejected: " + result.errors());
    }
  }

  private static <T> T asSystem(Supplier<T> action) {
    SecurityContext previous = SecurityContextHolder.getContext();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            USER, null, List.of(new SimpleGrantedAuthority("ROLE_DATENMANAGER"))));
    SecurityContextHolder.setContext(context);
    try {
      return action.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
