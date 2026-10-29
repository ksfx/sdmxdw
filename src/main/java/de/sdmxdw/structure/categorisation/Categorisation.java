package de.sdmxdw.structure.categorisation;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("categorisation")
public record Categorisation(@Id Long id, Long dataflowId, Long categoryId) {

  public static Categorisation createNew(Long dataflowId, Long categoryId) {
    return new Categorisation(null, dataflowId, categoryId);
  }
}
