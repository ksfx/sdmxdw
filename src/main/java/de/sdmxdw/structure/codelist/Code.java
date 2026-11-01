package de.sdmxdw.structure.codelist;

import org.springframework.data.annotation.Id;

/** A code of a codelist; the description (optional) explains the code beyond its name. */
public record Code(@Id Long id, String code, String name, String description) {

  public Code(Long id, String code, String name) {
    this(id, code, name, null);
  }
}
