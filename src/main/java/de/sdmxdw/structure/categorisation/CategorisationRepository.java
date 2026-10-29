package de.sdmxdw.structure.categorisation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface CategorisationRepository extends ListCrudRepository<Categorisation, Long> {

  Optional<Categorisation> findByDataflowId(Long dataflowId);

  List<Categorisation> findByCategoryId(Long categoryId);
}
