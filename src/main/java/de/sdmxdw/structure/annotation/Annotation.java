package de.sdmxdw.structure.annotation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An SDMX annotation of a structure artefact (#15): all fields optional as in SDMX. The text is
 * stored in one language (written as English in SDMX-ML).
 */
@Table("annotation")
public record Annotation(
    @Id Long id,
    AnnotationTarget targetType,
    Long targetId,
    int position,
    String annotationId,
    String annotationType,
    String title,
    String text,
    String url,
    String updatedBy,
    Instant updatedAt) {

  /** The value of .Stat style annotations: the title, or the text if the title is empty. */
  public String value() {
    return title != null && !title.isBlank() ? title.strip() : text == null ? "" : text.strip();
  }
}
