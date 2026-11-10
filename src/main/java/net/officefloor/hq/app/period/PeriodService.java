package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's periods: added on the screen, each a name unique on the sheet (without regard to
 * case) and a from and to date, both inclusive; no two periods on a sheet overlap. A period can be
 * closed once: closing fixes its due-total at that moment, and no claim dated inside a closed
 * period can be added, edited, opened, settled or voided. A steward can reopen a closed period, which
 * makes its due-total live again.
 */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Audit audit;
    private final RollupService rollup;
    private final RoleService roles;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetRows, Audit audit,
            RollupService rollup, RoleService roles) {
        this.rollup = rollup;
        this.roles = roles;
        this.periods = periods;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate from, LocalDate to) {
        roles.refuseViewer(sheetId, user);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period's to-date cannot be before its from-date.");
        }
        sheets.load(sheetId, user);
        // hold the sheet so two periods added at the same moment are checked one after the other
        sheetRows.findLockedById(sheetId);
        if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet.");
        }
        if (periods.existsBySheetIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, to, from)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another.");
        }
        Period added;
        try {
            added = periods.saveAndFlush(new Period(sheetId, name, from, to));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet.");
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + added.getName() + " from="
                + added.getFromDate() + " to=" + added.getToDate());
        return added;
    }

    /** Close a period, fixing its due-total now; refused (403) for a role the permission matrix does not allow and (409) when it is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long id) {
        roles.refuseUnlessPermitted(sheetId, user, "close");
        // hold the sheet so no claim changes while the period's due-total is fixed
        sheetRows.findLockedById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed.");
        }
        period.close(rollup.dueBetweenByPerson(sheetId, period.getFromDate(), period.getToDate()));
        Period saved = periods.saveAndFlush(period);
        BigDecimal due = saved.getClosedDue();
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + saved.getName()
                + " total=" + due.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        return saved;
    }

    /**
     * Reopen a closed period, making its due-total live again; refused (403) for a role the
     * permission matrix does not allow and (409) when the period is not closed.
     */
    @Transactional
    public Period reopen(String sheetId, String user, long id) {
        sheetRows.findLockedById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet."));
        roles.refuseUnlessPermitted(sheetId, user, "reopen");
        if (!period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is not closed.");
        }
        period.reopen();
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + saved.getName());
        return saved;
    }

    /**
     * Refuse (423) a change to a claim dated inside a closed period. Holds the sheet first, so a
     * period cannot close while the change is under way.
     */
    public void refuseIfClosed(String sheetId, LocalDate date) {
        if (isClosed(sheetId, date)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "That claim's date is inside a closed period.");
        }
    }

    /**
     * Whether a claim dated on this day lies inside a closed period. Holds the sheet first, so a
     * period cannot close while a change that relies on the answer is under way.
     */
    public boolean isClosed(String sheetId, LocalDate date) {
        sheetRows.findLockedById(sheetId);
        return periods.existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, date,
                date);
    }
}
