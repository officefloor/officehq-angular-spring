package net.officefloor.hq.app.person;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonMergeRepository extends JpaRepository<PersonMerge, Long> {

    /** The last merge into a person. */
    Optional<PersonMerge> findFirstByIntoIdOrderByIdDesc(Long intoId);
}
