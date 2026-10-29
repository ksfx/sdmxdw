package de.sdmxdw.microdata;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface MicrodataCategorisationRepository
    extends ListCrudRepository<MicrodataCategorisation, Long> {

  Optional<MicrodataCategorisation> findByMicrodataFlowId(Long microdataFlowId);

  List<MicrodataCategorisation> findByCategoryId(Long categoryId);
}
