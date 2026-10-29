package de.sdmxdw.structure.category;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface CategorySchemeRepository extends ListCrudRepository<CategoryScheme, Long> {

  @Query("select exists(select 1 from category where id = :categoryId)")
  boolean existsCategoryById(@Param("categoryId") Long categoryId);

  // updates only the scheme row - saving the aggregate would delete and re-insert all categories
  @Modifying
  @Query("update category_scheme set description = :description where id = :id")
  void updateDescription(@Param("id") Long id, @Param("description") String description);
}
