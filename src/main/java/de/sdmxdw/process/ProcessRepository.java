package de.sdmxdw.process;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface ProcessRepository extends ListCrudRepository<Process, Long> {

  List<Process> findAllByOrderByNameAsc();

  Optional<Process> findByFolder(String folder);

  Optional<Process> findByName(String name);

  List<Process> findByRunAfterProcessId(Long processId);
}
