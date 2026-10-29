package de.sdmxdw.process;

import de.sdmxdw.microdata.FacetSlot;
import de.sdmxdw.microdata.FieldDataType;
import de.sdmxdw.microdata.MicrodataFlow;
import de.sdmxdw.microdata.MicrodataFlowService;
import de.sdmxdw.structure.codelist.Codelist;
import de.sdmxdw.structure.codelist.CodelistService;
import de.sdmxdw.structure.conceptscheme.ConceptScheme;
import de.sdmxdw.structure.conceptscheme.ConceptSchemeService;
import de.sdmxdw.structure.dataflow.Dataflow;
import de.sdmxdw.structure.dataflow.DataflowService;
import de.sdmxdw.structure.dsd.DataStructureDefinition;
import de.sdmxdw.structure.dsd.DataStructureDefinitionService;
import de.sdmxdw.structure.dsd.DataType;
import org.springframework.stereotype.Component;

/** Test structures: a time series dataflow and two linked microdata flows. Needs a DM context. */
@Component
class ProcessFixtures {

  private static int counter;

  private final ConceptSchemeService conceptSchemeService;
  private final CodelistService codelistService;
  private final DataStructureDefinitionService dsdService;
  private final DataflowService dataflowService;
  private final MicrodataFlowService microdataFlowService;

  ProcessFixtures(
      ConceptSchemeService conceptSchemeService,
      CodelistService codelistService,
      DataStructureDefinitionService dsdService,
      DataflowService dataflowService,
      MicrodataFlowService microdataFlowService) {
    this.conceptSchemeService = conceptSchemeService;
    this.codelistService = codelistService;
    this.dsdService = dsdService;
    this.dataflowService = dataflowService;
    this.microdataFlowService = microdataFlowService;
  }

  static String suffix() {
    return "P" + (++counter) + "_" + System.nanoTime() % 100000;
  }

  /** FREQ (A, Q) . TIME_PERIOD -> OBS_VALUE; ref "TST:DF_<suffix>(1.0.0)". */
  Dataflow dataflow(String suffix) {
    ConceptScheme cs =
        conceptSchemeService.createScheme("TST", "CS_" + suffix, "1.0.0", "Concepts");
    cs = conceptSchemeService.addConcept(cs.id(), "FREQ", "Frequency");
    cs = conceptSchemeService.addConcept(cs.id(), "TIME_PERIOD", "Time");
    cs = conceptSchemeService.addConcept(cs.id(), "OBS_VALUE", "Value");
    Codelist cl = codelistService.createCodelist("TST", "CL_" + suffix, "1.0.0", "Freq");
    cl = codelistService.addCode(cl.id(), "A", "Annual");
    cl = codelistService.addCode(cl.id(), "Q", "Quarterly");
    DataStructureDefinition dsd = dsdService.createDsd("TST", "DSD_" + suffix, "1.0.0", "DSD");
    dsd = dsdService.addDimension(dsd.id(), id(cs, "FREQ"), cl.id(), DataType.STRING);
    dsd = dsdService.addDimension(dsd.id(), id(cs, "TIME_PERIOD"), null, DataType.DATE);
    dsd = dsdService.addMeasure(dsd.id(), id(cs, "OBS_VALUE"), DataType.NUMBER);
    return dataflowService.createDataflow("TST", "DF_" + suffix, "1.0.0", "Flow", dsd.id());
  }

  record Micro(MicrodataFlow counterparties, MicrodataFlow loans) {}

  /** CP (NAME) and LOAN (CURRENCY coded+indexed, AMOUNT, COUNTERPARTY -> CP, group COLLATERAL). */
  Micro microdata(String suffix) {
    MicrodataFlow cp =
        microdataFlowService.createFlow("TST", "MDF_CP_" + suffix, "1.0.0", "Counterparties", null);
    microdataFlowService.addField(cp.id(), "NAME", "Name", FieldDataType.STRING, null, true, null);
    MicrodataFlow loan =
        microdataFlowService.createFlow("TST", "MDF_LOAN_" + suffix, "1.0.0", "Loans", null);
    Codelist ccy = codelistService.createCodelist("TST", "CL_CCY_" + suffix, "1.0.0", "Currency");
    ccy = codelistService.addCode(ccy.id(), "CHF", "Swiss franc");
    ccy = codelistService.addCode(ccy.id(), "EUR", "Euro");
    microdataFlowService.addField(
        loan.id(), "CURRENCY", "Currency", FieldDataType.STRING, ccy.id(), true, FacetSlot.facet1);
    microdataFlowService.addField(
        loan.id(), "AMOUNT", "Amount", FieldDataType.NUMBER, null, true, null);
    microdataFlowService.addReferenceField(
        loan.id(), "COUNTERPARTY", "Counterparty", cp.id(), true, null);
    microdataFlowService.addGroupField(
        loan.id(), "COLLATERAL", "TYPE", "Type", FieldDataType.STRING, null, false);
    microdataFlowService.addGroupField(
        loan.id(), "COLLATERAL", "VALUE", "Value", FieldDataType.NUMBER, null, false);
    return new Micro(cp, loan);
  }

  static String ref(Dataflow d) {
    return d.agencyId() + ":" + d.artefactId() + "(" + d.version() + ")";
  }

  static String ref(MicrodataFlow f) {
    return f.agencyId() + ":" + f.artefactId() + "(" + f.version() + ")";
  }

  private static Long id(ConceptScheme cs, String code) {
    return cs.concepts().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().id();
  }
}
