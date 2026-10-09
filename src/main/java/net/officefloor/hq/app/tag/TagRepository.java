package net.officefloor.hq.app.tag;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {

    /** All tags in name order. */
    List<Tag> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
