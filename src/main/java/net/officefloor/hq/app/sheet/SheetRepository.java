package net.officefloor.hq.app.sheet;

import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** The sheet, locked for the rest of the transaction so only one change at a time takes its next number. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sheet s where s.id = :id")
    Optional<Sheet> findLockedById(String id);

    /** A sheet's ancestors, nearest first: its parent, its parent's parent, and so on. */
    default List<String> ancestors(String id) {
        List<String> chain = new ArrayList<>();
        String next = findById(id).map(Sheet::getParentId).orElse(null);
        while (next != null && !next.equals(id) && !chain.contains(next)) {
            chain.add(next);
            next = findById(next).map(Sheet::getParentId).orElse(null);
        }
        return chain;
    }
}
