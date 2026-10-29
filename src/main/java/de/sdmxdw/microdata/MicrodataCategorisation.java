package de.sdmxdw.microdata;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("microdata_categorisation")
public record MicrodataCategorisation(@Id Long id, Long microdataFlowId, Long categoryId) {

  public static MicrodataCategorisation createNew(Long microdataFlowId, Long categoryId) {
    return new MicrodataCategorisation(null, microdataFlowId, categoryId);
  }
}
