package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.math.BigDecimal;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The periods of a sheet. */
@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Audit audit;
    private final Rollup rollup;
    private final RoleService roles;

    public PeriodService(PeriodRepository periods, SheetService sheets, SheetRepository sheetStore, Audit audit,
            Rollup rollup, RoleService roles) {
        this.roles = roles;
        this.periods = periods;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** The sheet's periods, by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromDateAsc(sheetId);
    }

    /** Add a period; the name is stored trimmed. Refused when the name is in use on the sheet, the
     *  to-date is before the from-date, or it shares a day with another period of the sheet. With no
     *  from-date it starts on the sheet's today. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate fromDate, LocalDate to) {
        sheets.load(sheetId, user);
        // The sheet is locked so two periods added at the same moment cannot overlap.
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        LocalDate from = fromDate != null ? fromDate : today;
        String trimmed = name.trim();
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts");
        }
        List<Period> existing = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        if (existing.stream().anyMatch(p -> p.getName().equals(trimmed))) {
            throw nameInUse();
        }
        if (existing.stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another on this sheet");
        }
        Period added;
        try {
            added = periods.saveAndFlush(new Period(sheetId, trimmed, from, to));
        } catch (DataIntegrityViolationException raced) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + added.getName()
                + " from=" + added.getFromDate() + " to=" + added.getToDate());
        return added;
    }

    /** Close a period, fixing its due-total as it is now; refused when it is already closed. The sheet
     *  is locked so no claim in it changes while its total is taken. */
    @Transactional
    public Period close(String sheetId, String user, String name) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed");
        }
        BigDecimal total = rollup.dueTotalBetween(sheetId, period.getFromDate(), period.getToDate());
        period.close(total);
        Period closed = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + closed.getName()
                + " total=" + total.toPlainString());
        return closed;
    }

    /** Reopen a closed period, making its due-total live again; only a steward may, and it is refused
     *  when the period is open. */
    @Transactional
    public Period reopen(String sheetId, String user, String name) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can reopen a period");
        }
        Period period = periods.findBySheetIdAndName(sheetId, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period on this sheet"));
        if (!period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is not closed");
        }
        period.reopen();
        Period reopened = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + reopened.getName());
        return reopened;
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That period name is already in use on this sheet");
    }
}
