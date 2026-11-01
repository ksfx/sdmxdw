package de.sdmxdw.data.series;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("series")
public record Series(@Id Long id, Long dataflowId, String seriesKey) {

  public static Series createNew(Long dataflowId, String seriesKey) {
    return new Series(null, dataflowId, seriesKey);
  }
}
