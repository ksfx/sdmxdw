package de.sdmxdw.data.series;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

public interface SeriesRepository extends ListCrudRepository<Series, Long> {

  Optional<Series> findByDataflowIdAndSeriesKey(Long dataflowId, String seriesKey);

  List<Series> findByDataflowId(Long dataflowId);
}
