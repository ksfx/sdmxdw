package de.sdmxdw.process;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("process_secret")
public record ProcessSecret(
    @Id Long id, String name, String valueEncrypted, String updatedBy, Instant updatedAt) {}
