package net.officefloor.hq.app.period;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Permission;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.Sheet;
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

    /** Add one period per calendar month from the from-date's month to the to-date's month, each running from
     * the first to the last day of its month and named yyyy-mm; a month whose name is taken on the sheet, or whose
     * days overlap one of the sheet's periods, is left out. A to-date before the from-date is refused. */
    @Transactional
    public List<Period> generate(String user, String sheetId, LocalDate from, LocalDate to) {
        sheets.lock(sheetId);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date.");
        }
        List<Period> existing = periods.findBySheetIdOrderByFromAsc(sheetId);
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (periods.existsBySheetIdAndNameIgnoreCase(sheetId, name)
                    || existing.stream().anyMatch(p -> p.overlaps(first, last))) {
                continue;
            }
            Period period = periods.saveAndFlush(new Period(sheetId, name, first, last));
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user + " period=" + period.getName()
                    + " from=" + period.getFrom() + " to=" + period.getTo());
            made.add(period);
        }
        return made;
    }

    /** Close a period, fixing each person's due-total for it, and so its own, as they are now; only a role the permission matrix allows may, and closing one already closed is refused. A soft-closed one can be closed, fixing its figures afresh. */
    @Transactional
    public Period close(String user, String sheetId, Long id) {
        // Locking the sheet keeps claims from changing while the total is taken.
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        settings.requirePermitted(sheetId, user, Permission.CLOSE);
        if (period.isHardClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed.");
        }
        return closeNow(user, sheetId, period, false);
    }

    /** Soft-close a period: its due-totals are fixed as by a close, but claims dated in it can still be added and
     * changed. The same roles as may close may; soft-closing one already closed, soft or not, is refused. */
    @Transactional
    public Period softClose(String user, String sheetId, Long id) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        settings.requirePermitted(sheetId, user, Permission.CLOSE);
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That period is already closed.");
        }
        period.close(rollup.dueTotals(sheetId, period.getFrom(), period.getTo()), true);
        periods.flush();
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + user + " period=" + period.getName()
                + " total=" + period.getClosedTotal().toPlainString());
        return period;
    }

    /** Close every open period of the sheet whose to-date plus the sheet's grace days is before its today, in
     * order of their to-dates, each as a close by hand would; none when the sheet has no grace days. The caller
     * holds the sheet's lock. */
    @Transactional
    public List<Period> closeLapsed(String user, String sheetId) {
        Sheet sheet = settings.sheet(sheetId);
        Integer graceDays = sheet.getGraceDays();
        if (graceDays == null) {
            return List.of();
        }
        LocalDate today = sheet.getToday();
        return periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> !p.isClosed() && p.getTo().plusDays(graceDays).isBefore(today))
                .sorted(Comparator.comparing(Period::getTo))
                .map(p -> closeNow(user, sheetId, p, true))
                .toList();
    }

    /** Fix the period's due-totals as they are now and write that it closed, {@code auto=yes} at the end when it
     * was closed by a change of the sheet's today. */
    private Period closeNow(String user, String sheetId, Period period, boolean auto) {
        period.close(rollup.dueTotals(sheetId, period.getFrom(), period.getTo()), false);
        periods.flush();
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user + " period=" + period.getName()
                + " total=" + period.getClosedTotal().toPlainString() + (auto ? " auto=yes" : ""));
        return period;
    }

    /** Reopen a closed period, soft or not, making its due-total live again; only a role the permission matrix allows (a steward by the standing rule) may, and reopening one that is
     * open is refused. */
    @Transactional
    public Period reopen(String user, String sheetId, Long id) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period."));
        settings.requirePermitted(sheetId, user, Permission.REOPEN);
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

    /** Refuse a change to a claim dated in one of the sheet's closed periods; a soft-closed one allows it. */
    @Transactional(readOnly = true)
    public void requireOpen(String sheetId, LocalDate date) {
        if (periods.findBySheetIdOrderByFromAsc(sheetId).stream().anyMatch(p -> p.isHardClosed() && p.contains(date))) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "That claim is dated in a closed period.");
        }
    }
}
