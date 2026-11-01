package de.sdmxdw.microdata;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("microdata_flow")
public record MicrodataFlow(
    @Id Long id,
    String agencyId,
    String artefactId,
    String version,
    String name,
    String description,
    Long linkedDataflowId) {

  public static MicrodataFlow createNew(
      String agencyId, String artefactId, String version, String name, String description) {
    return new MicrodataFlow(null, agencyId, artefactId, version, name, description, null);
  }

  public MicrodataFlow withDescription(String newDescription) {
    return new MicrodataFlow(
        id, agencyId, artefactId, version, name, newDescription, linkedDataflowId);
  }

  public MicrodataFlow withLinkedDataflowId(Long newLinkedDataflowId) {
    return new MicrodataFlow(
        id, agencyId, artefactId, version, name, description, newLinkedDataflowId);
  }
}
