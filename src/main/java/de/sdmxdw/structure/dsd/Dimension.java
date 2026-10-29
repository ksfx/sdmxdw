package de.sdmxdw.structure.dsd;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("dsd_dimension")
public record Dimension(
    @Id Long id, int position, Long conceptId, Long codelistId, DataType dataType) {}
