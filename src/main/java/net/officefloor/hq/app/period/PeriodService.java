package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
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
    private final Rollup rollup;
    private final RoleService roles;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetRepository sheets, Rollup rollup, RoleService roles,
            Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.rollup = rollup;
        this.roles = roles;
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

    /** Close a period, fixing each person's due-total for it and its own as it is now; refused when it is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long periodId) {
        // Locking the sheet makes the close and any claim change on the sheet go one after the other.
        sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        Period period = periods.findByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (period.isClosed()) {
            throw new PeriodRefusedException("The period is already closed.");
        }
        period.close(rollup.dueBetweenByPerson(sheetId, period.getFromDate(), period.getToDate()));
        periods.saveAndFlush(period);
        BigDecimal total = period.getClosedTotal();
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user
                + " period=" + period.getName() + " total=" + total.toPlainString());
        return period;
    }

    /** Reopen a closed period, making its due-total live again; by a steward only, and refused when it
     *  is open. */
    @Transactional
    public Period reopen(String sheetId, String user, long periodId) {
        // Locking the sheet makes the reopen and any claim change on the sheet go one after the other.
        sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        Period period = periods.findByIdAndSheetId(periodId, sheetId).orElseThrow(PeriodNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new PeriodStewardOnlyException();
        }
        if (!period.isClosed()) {
            throw new PeriodRefusedException("The period is not closed.");
        }
        period.reopen();
        periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + period.getName());
        return period;
    }
}
