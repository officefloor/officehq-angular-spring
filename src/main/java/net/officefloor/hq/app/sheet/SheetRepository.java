package net.officefloor.hq.app.sheet;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SheetRepository extends JpaRepository<Sheet, String> {

    /** Move the sheet's last claim number on by one; the row stays locked until the transaction
     *  ends, so two claims opened at once get one number each. */
    @Modifying
    @Query("update Sheet s set s.lastClaimNumber = s.lastClaimNumber + 1 where s.id = :id")
    int advanceClaimNumber(String id);

    @Query("select s.lastClaimNumber from Sheet s where s.id = :id")
    int lastClaimNumber(String id);

    /** Move the sheet's last X-number (for claims in no period) on by one; the row stays locked
     *  until the transaction ends. */
    @Modifying
    @Query("update Sheet s set s.lastUnperiodNumber = s.lastUnperiodNumber + 1 where s.id = :id")
    int advanceUnperiodNumber(String id);

    @Query("select s.lastUnperiodNumber from Sheet s where s.id = :id")
    int lastUnperiodNumber(String id);

    /** Move the sheet's last settle batch number on by one; the row stays locked until the
     *  transaction ends. */
    @Modifying
    @Query("update Sheet s set s.lastSettleBatch = s.lastSettleBatch + 1 where s.id = :id")
    int advanceSettleBatch(String id);

    @Query("select s.lastSettleBatch from Sheet s where s.id = :id")
    Integer lastSettleBatch(String id);

    /** How many levels up a sheet's rates are taken from: its parent, its parent's parent, and so on. */
    int ANCESTOR_LEVELS = 3;

    /** The ids of a sheet's ancestors, nearest first: its parent, then the parent's parent, up to
     *  {@link #ANCESTOR_LEVELS} levels up; empty when it has no parent. */
    default List<String> ancestors(String id) {
        List<String> up = new ArrayList<>();
        String at = id;
        while (up.size() < ANCESTOR_LEVELS) {
            String parent = findById(at).map(Sheet::getParentId).orElse(null);
            if (parent == null || parent.equals(id) || up.contains(parent)) {
                break;
            }
            up.add(parent);
            at = parent;
        }
        return up;
    }
}
