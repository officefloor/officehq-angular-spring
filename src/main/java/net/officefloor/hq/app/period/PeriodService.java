package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Permission;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import org.springframework.context.event.EventListener;
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

    /** Generate one period per calendar month from the from-date's month (a from-date left out is
     *  the sheet's today) to the to-date's month, each from the first to the last day of its month
     *  and named yyyy-mm; a month whose name is already a period's, or whose days overlap a period
     *  on the sheet, is left out. Refused when the to-date is before the from-date. Returns the
     *  periods made, by from-date. */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate fromOrNull, LocalDate to) {
        sheets.load(sheetId, user);
        LocalDate from = fromOrNull != null ? fromOrNull : sheets.today(sheetId);
        sheets.lock(sheetId);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date");
        }
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            String name = month.toString();
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            if (existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(name) || p.overlaps(first, last))) {
                continue;
            }
            Period period = periods.saveAndFlush(new Period(sheetId, name, first, last));
            existing.add(period);
            made.add(period);
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user
                    + " period=" + name + " from=" + first + " to=" + last);
        }
        return made;
    }

    /** Close a period, fixing each person's due-total for it, and its own (their sum), at the
     *  figures they show now (afresh for a soft-closed one); refused (403) when the user's role may
     *  not close by the sheet's permission matrix, and (409) when it is already closed. */
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
        closeNow(sheetId, user, period, "");
        return period;
    }

    /** Soft-close an open period, fixing its figures as a close does while claims dated inside it
     *  can still be changed; refused (403) when the user's role may not close by the sheet's
     *  permission matrix, and (409) when it is already closed or soft-closed. */
    @Transactional
    public Period softClose(String sheetId, String user, long id) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        settings.refuseUnlessAllowed(sheetId, user, Permission.CLOSE);
        if (period.isClosed() || period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed");
        }
        period.softClose(rollup.dueByPersonBetween(sheetId, period.getFrom(), period.getTo()));
        periods.flush();
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + user
                + " period=" + period.getName() + " total=" + period.getClosedTotal().toPlainString());
        return period;
    }

    /** Once the sheet's today changes, close every open period whose to-date plus the sheet's
     *  grace days (when set) is before the new today, in order of their to-dates, each as a close
     *  by hand would; a soft-closed one keeps the figures it fixed. */
    @EventListener
    @Transactional
    public void closePastGrace(TodayChanged change) {
        Integer graceDays = sheets.find(change.sheetId()).map(Sheet::getGraceDays).orElse(null);
        if (graceDays == null) {
            return;
        }
        periods.findBySheetIdOrderByFromAsc(change.sheetId()).stream()
                .filter(p -> !p.isClosed() && !p.isSoftClosed() && p.getTo().plusDays(graceDays).isBefore(change.today()))
                .sorted(Comparator.comparing(Period::getTo))
                .forEach(p -> closeNow(change.sheetId(), change.user(), p, " auto=yes"));
    }

    /** Fix the period's figures as they show now and audit it, with any trailing fields. */
    private void closeNow(String sheetId, String user, Period period, String trailing) {
        period.close(rollup.dueByPersonBetween(sheetId, period.getFrom(), period.getTo()));
        periods.flush();
        BigDecimal total = period.getClosedTotal();
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user
                + " period=" + period.getName() + " total=" + total.toPlainString() + trailing);
    }

    /** Reopen a closed or soft-closed period, its due-total live again; refused (403) unless the
     *  user's role may reopen by the sheet's permission matrix (by default a steward), and (409)
     *  when it is neither closed nor soft-closed. */
    @Transactional
    public Period reopen(String sheetId, String user, long id) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        settings.refuseUnlessAllowed(sheetId, user, Permission.REOPEN);
        if (!period.isClosed() && !period.isSoftClosed()) {
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

    /** Whether a soft-closed period of the sheet contains {@code date}. */
    @Transactional(readOnly = true)
    public boolean softClosedOn(String sheetId, LocalDate date) {
        return periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .anyMatch(p -> p.isSoftClosed() && p.contains(date));
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
