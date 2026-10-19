package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PeriodService {

    private final PeriodRepository periods;
    private final SheetService sheets;
    private final RollupService rollup;
    private final SettingService settings;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, RollupService rollup,
            SettingService settings, Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.rollup = rollup;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's periods by from-date. */
    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** Add a period; one whose to-date is before its from-date, whose name is taken on the sheet, or that
     * overlaps another of the sheet's periods is refused. */
    @Transactional
    public Period add(String user, String sheetId, String name, LocalDate from, LocalDate to) {
        // Locking the sheet adds its periods one at a time, so two at once cannot overlap.
        sheets.lock(sheetId);
        String trimmed = name.trim();
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A period cannot end before it starts.");
        }
        if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
        }
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period overlaps another.");
        }
        Period period;
        try {
            period = periods.saveAndFlush(new Period(sheetId, trimmed, from, to));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + period.getName()
                + " from=" + period.getFrom() + " to=" + period.getTo());
        return period;
    }

    /** Close a period, fixing each person's due-total for it, and so its own, as they are now; closing one already closed is refused. */
    @Transactional
    public Period close(String user, String sheetId, Long id) {
        // Locking the sheet keeps claims from changing while the total is taken.
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed.");
        }
        period.close(rollup.dueTotals(sheetId, period.getFrom(), period.getTo()));
        periods.flush();
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + period.getName()
                + " total=" + period.getClosedTotal().toPlainString());
        return period;
    }

    /** Reopen a closed period, making its due-total live again; only a steward may, and reopening one that is
     * open is refused. */
    @Transactional
    public Period reopen(String user, String sheetId, Long id) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can reopen a period.");
        }
        if (!period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is not closed.");
        }
        period.reopen();
        periods.flush();
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + period.getName());
        return period;
    }

    /** The sheet's period a date is in, if any. */
    @Transactional(readOnly = true)
    public Optional<Period> periodOf(String sheetId, LocalDate date) {
        return periods.findBySheetIdOrderByFromAsc(sheetId).stream().filter(p -> p.contains(date)).findFirst();
    }

    /** Refuse a change to a claim dated in one of the sheet's closed periods. */
    @Transactional(readOnly = true)
    public void requireOpen(String sheetId, LocalDate date) {
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.isClosed() && p.contains(date))) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "That claim is dated in a closed period.");
        }
    }
}
