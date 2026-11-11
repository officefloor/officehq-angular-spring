package net.officefloor.hq.app.statement;

import java.util.List;
import net.officefloor.hq.app.person.Person;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    /** A sheet's statements, each person's in their order (the last is the person's last statement). */
    List<Statement> findBySheetIdOrderBySeqAscIdAsc(String sheetId);

    List<Statement> findBySheetIdAndPersonIdOrderBySeqAscIdAsc(String sheetId, Long personId);

    /**
     * The statements of one person follow them to another (on the other's sheet): counted with the other's own, placed
     * before them so the other's last statement stays theirs (or, having none, becomes the last of those that follow).
     */
    default void follow(Person from, Person into) {
        List<Statement> theirs = findBySheetIdAndPersonIdOrderBySeqAscIdAsc(from.getSheetId(), from.getId());
        List<Statement> own = findBySheetIdAndPersonIdOrderBySeqAscIdAsc(into.getSheetId(), into.getId());
        long first = own.isEmpty() ? 1 : own.get(0).getSeq() - theirs.size();
        for (int i = 0; i < theirs.size(); i++) {
            theirs.get(i).moveTo(into.getSheetId(), into.getId(), first + i);
        }
        saveAllAndFlush(theirs);
    }
}
