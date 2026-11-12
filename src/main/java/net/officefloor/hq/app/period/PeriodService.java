package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's periods: each uniquely named on the sheet, none overlapping another. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetRepository sheets;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        // Locking the sheet makes two users' periods go one after the other, so neither misses an overlap.
        sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        String n = name.trim();
        if (from.isAfter(to)) {
            throw new PeriodRefusedException("The from-date must be on or before the to-date.");
        }
        List<Period> existing = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        if (existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(n))) {
            throw new PeriodRefusedException("That period name is already in use.");
        }
        existing.stream().filter(p -> p.overlaps(from, to)).findFirst().ifPresent(p -> {
            throw new PeriodRefusedException("The period overlaps " + p.getName() + ".");
        });
        Period period;
        try {
            period = periods.saveAndFlush(new Period(sheetId, n, from, to));
        } catch (DataIntegrityViolationException e) {
            throw new PeriodRefusedException("That period name is already in use.");
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user
                + " period=" + n + " from=" + from + " to=" + to);
        return period;
    }
}
