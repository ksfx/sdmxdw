package de.sdmxdw.process;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface ProcessSecretRepository extends ListCrudRepository<ProcessSecret, Long> {

  Optional<ProcessSecret> findByName(String name);

  List<ProcessSecret> findAllByOrderByNameAsc();
}
