package net.officefloor.hq.app.rollup;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures of a sheet consolidated over it and all its descendants (the sheets naming it as their
 * parent, theirs, and so on), each sheet's as that sheet shows them now.
 */
@Service
public class Consolidation {

    /** The number of people, and the due-totals and overdue-totals added up. */
    public record Consolidated(int peopleCount, BigDecimal dueTotal, BigDecimal overdueTotal) {
    }

    private final SheetRepository sheets;
    private final Rollups rollups;

    public Consolidation(SheetRepository sheets, Rollups rollups) {
        this.sheets = sheets;
        this.rollups = rollups;
    }

    /** The sheet's figures added to those of all its descendants. */
    @Transactional(readOnly = true)
    public Consolidated of(String sheetId) {
        List<String> all = new ArrayList<>();
        all.add(sheetId);
        all.addAll(sheets.descendants(sheetId));
        int people = 0;
        BigDecimal due = BigDecimal.ZERO.setScale(2);
        BigDecimal overdue = BigDecimal.ZERO.setScale(2);
        for (String id : all) {
            Rollups.Rollup rollup = rollups.take(id);
            people += rollup.people().size();
            due = due.add(rollup.dueTotal());
            overdue = overdue.add(rollup.overdueTotal());
        }
        return new Consolidated(people, due, overdue);
    }
}
