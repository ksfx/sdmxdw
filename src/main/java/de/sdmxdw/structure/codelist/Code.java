package de.sdmxdw.structure.codelist;

import org.springframework.data.annotation.Id;

public record Code(@Id Long id, String code, String name) {}
