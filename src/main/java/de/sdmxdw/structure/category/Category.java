package de.sdmxdw.structure.category;

import org.springframework.data.annotation.Id;

public record Category(@Id Long id, Long parentCategoryId, String code, String name) {}
