package de.sdmxdw.structure.conceptscheme;

import org.springframework.data.annotation.Id;

public record Concept(@Id Long id, String code, String name) {}
