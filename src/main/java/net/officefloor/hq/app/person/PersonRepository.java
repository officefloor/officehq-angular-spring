package net.officefloor.hq.app.person;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PersonRepository extends JpaRepository<Person, Long> {

    List<Person> findBySheetIdOrderByIdAsc(String sheetId);

    Optional<Person> findByIdAndSheetId(Long id, String sheetId);

    /** The person, locked until the transaction ends, so two statements of them never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Person> findWithLockByIdAndSheetId(Long id, String sheetId);

    boolean existsBySheetIdAndNameIgnoreCase(String sheetId, String name);

    boolean existsBySheetIdAndNameIgnoreCaseAndIdNot(String sheetId, String name, Long id);
}
