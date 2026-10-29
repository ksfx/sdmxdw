package de.sdmxdw.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import de.sdmxdw.web.rest.SdmxDataWriter.DataMessage;
import de.sdmxdw.web.rest.SdmxDataWriter.Format;
import de.sdmxdw.web.rest.SdmxDataWriter.ObsData;
import de.sdmxdw.web.rest.SdmxDataWriter.SeriesData;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

class SdmxDataWriterTest {

  private static final Instant PREPARED = Instant.parse("2026-10-28T08:00:00Z");

  private DataMessage sample() {
    return new DataMessage(
        "BIS",
        "DF_CREDIT_GAP",
        "1.0",
        List.of("FREQ", "BORROWERS_CTY"),
        "TIME_PERIOD",
        List.of("OBS_VALUE"),
        List.of(
            new SeriesData(
                List.of("Q", "DE"),
                List.of(
                    new ObsData("2020-Q1", List.of("138.1")),
                    new ObsData("2020-Q2", List.of("143.5"))))),
        Map.of(),
        Map.of());
  }

  private String write(Format format) {
    StringWriter out = new StringWriter();
    SdmxDataWriter.write(sample(), format, PREPARED, out);
    return out.toString();
  }

  private Document parse(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
  }

  @Test
  void writesWellFormedSdmx21StructureSpecificData() throws Exception {
    Document doc = parse(write(Format.STRUCTURE_SPECIFIC_21));

    Element root = doc.getDocumentElement();
    assertThat(root.getLocalName()).isEqualTo("StructureSpecificData");
    assertThat(root.getNamespaceURI())
        .isEqualTo("http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message");
    Element ref = (Element) doc.getElementsByTagName("Ref").item(0);
    assertThat(ref.getAttribute("id")).isEqualTo("DF_CREDIT_GAP");
    Element series = (Element) doc.getElementsByTagName("Series").item(0);
    assertThat(series.getAttribute("FREQ")).isEqualTo("Q");
    assertThat(series.getAttribute("BORROWERS_CTY")).isEqualTo("DE");
    assertThat(doc.getElementsByTagName("Obs").getLength()).isEqualTo(2);
    Element firstObs = (Element) doc.getElementsByTagName("Obs").item(0);
    assertThat(firstObs.getAttribute("TIME_PERIOD")).isEqualTo("2020-Q1");
    assertThat(firstObs.getAttribute("OBS_VALUE")).isEqualTo("138.1");
  }

  @Test
  void sdmx31UsesV31NamespaceAndUrnReference() throws Exception {
    String xml = write(Format.STRUCTURE_SPECIFIC_31);
    Document doc = parse(xml);

    assertThat(doc.getDocumentElement().getNamespaceURI())
        .isEqualTo("http://www.sdmx.org/resources/sdmxml/schemas/v3_1/message");
    assertThat(doc.getElementsByTagName("Ref").getLength()).isZero();
    assertThat(xml)
        .contains(
            "<common:StructureUsage>urn:sdmx:org.sdmx.infomodel.datastructure.Dataflow="
                + "BIS:DF_CREDIT_GAP(1.0)</common:StructureUsage>");
  }

  @Test
  void genericDataIsWellFormed() throws Exception {
    Document doc = parse(write(Format.GENERIC_21));
    assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("GenericData");
    assertThat(doc.getElementsByTagNameNS("*", "ObsValue").getLength()).isEqualTo(2);
  }

  @Test
  void csvEscapesSeparators() {
    assertThat(SdmxDataWriter.csv("a,b")).isEqualTo("\"a,b\"");
    assertThat(SdmxDataWriter.csv("say \"x\"")).isEqualTo("\"say \"\"x\"\"\"");
    assertThat(write(Format.CSV_10))
        .startsWith("DATAFLOW,FREQ,BORROWERS_CTY,TIME_PERIOD,OBS_VALUE\n");
  }
}
