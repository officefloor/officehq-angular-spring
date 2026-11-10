package net.officefloor.hq.app.statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** A sheet's statements, oldest first. */
    List<Statement> findBySheetIdOrderByIdAsc(String sheetId);

    /** A person's statements, oldest first. */
    default List<Statement> findBySheetIdAndPerson(String sheetId, Long personId) {
        return findBySheetIdOrderByIdAsc(sheetId).stream().filter(s -> Objects.equals(s.getPersonId(), personId))
                .toList();
    }

    /**
     * Move a person's statements to another person (a merge or a transfer): the other's count becomes
     * their own plus these, listed before the other's own so the other's last statement stays theirs
     * (or, having none, becomes the last of these).
     */
    default void moveStatements(String fromSheetId, Long fromPersonId, String toSheetId, Long toPersonId) {
        List<Statement> moving = findBySheetIdAndPerson(fromSheetId, fromPersonId);
        if (moving.isEmpty()) {
            return;
        }
        List<Statement> own = findBySheetIdAndPerson(toSheetId, toPersonId);
        List<Statement> after = new ArrayList<>();
        moving.forEach(s -> after.add(s.movedTo(toSheetId, toPersonId)));
        own.forEach(s -> after.add(s.movedTo(toSheetId, toPersonId)));
        deleteAll(moving);
        deleteAll(own);
        flush();
        after.forEach(this::saveAndFlush);
    }
}
