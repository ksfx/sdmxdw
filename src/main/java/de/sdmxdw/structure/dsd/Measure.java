package de.sdmxdw.structure.dsd;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("dsd_measure")
public record Measure(@Id Long id, Long conceptId, DataType dataType) {}
