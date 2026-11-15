package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Permission;
import net.officefloor.hq.app.settings.SettingsService;
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
    private final SettingsService settings;
    private final Audit audit;

    public PeriodService(PeriodRepository periods, SheetService sheets, RollupService rollup, SettingsService settings,
            Audit audit) {
        this.periods = periods;
        this.sheets = sheets;
        this.rollup = rollup;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** Add a period to the sheet (the sheet is created first if it was never loaded; a from-date left out is the sheet's today); refused when
     *  its from-date is after its to-date, its name is in use on the sheet or it overlaps another. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate fromOrNull, LocalDate to) {
        sheets.load(sheetId, user);
        LocalDate from = fromOrNull != null ? fromOrNull : sheets.today(sheetId);
        // held until the transaction ends, so two periods added at once are checked one after the other
        sheets.lock(sheetId);
        String trimmed = name.trim();
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The from-date is after the to-date");
        }
        List<Period> existing = periods.findBySheetIdOrderByFromAsc(sheetId);
        if (existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(trimmed))) {
            throw nameInUse();
        }
        if (existing.stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period overlaps another");
        }
        Period period;
        try {
            period = periods.saveAndFlush(new Period(sheetId, trimmed, from, to));
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user
                + " period=" + trimmed + " from=" + from + " to=" + to);
        return period;
    }

    /** Close a period, fixing each person's due-total for it, and its own (their sum), at the
     *  figures they show now; refused (403) when the user's role may not close by the sheet's
     *  permission matrix, and (409) when it is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long id) {
        // held until the transaction ends, so no claim in the period changes while it is closed
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        settings.refuseUnlessAllowed(sheetId, user, Permission.CLOSE);
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed");
        }
        period.close(rollup.dueByPersonBetween(sheetId, period.getFrom(), period.getTo()));
        periods.flush();
        BigDecimal total = period.getClosedTotal();
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user
                + " period=" + period.getName() + " total=" + total.toPlainString());
        return period;
    }

    /** Reopen a closed period, its due-total live again; refused (403) unless the user's role may
     *  reopen by the sheet's permission matrix (by default a steward), and (409) when it is not closed. */
    @Transactional
    public Period reopen(String sheetId, String user, long id) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        settings.refuseUnlessAllowed(sheetId, user, Permission.REOPEN);
        if (!period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is not closed");
        }
        period.reopen();
        periods.flush();
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + period.getName());
        return period;
    }

    /** The sheet's period whose dates contain {@code date}, if any. */
    @Transactional(readOnly = true)
    public Optional<Period> containing(String sheetId, LocalDate date) {
        return periods.findBySheetIdOrderByFromAsc(sheetId).stream().filter(p -> p.contains(date)).findFirst();
    }

    /** Refuse (423) a change to a claim dated {@code date} when a closed period contains it. */
    @Transactional(readOnly = true)
    public void refuseIfClosed(String sheetId, LocalDate date) {
        periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> p.isClosed() && p.contains(date))
                .findFirst()
                .ifPresent(p -> {
                    throw new ResponseStatusException(HttpStatus.LOCKED,
                            "The claim's date is in closed period " + p.getName());
                });
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The period name is in use on the sheet");
    }
}
