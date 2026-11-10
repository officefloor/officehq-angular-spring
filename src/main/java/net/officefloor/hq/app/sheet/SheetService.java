package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Loads a sheet by id; a sheet never loaded before is started empty, its today the real date. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;
    private final PeriodRepository periods;
    private final Audit audit;
    private final ClaimRepository claims;

    public SheetService(SheetRepository sheets, RollupService rollup, PeriodRepository periods, Audit audit,
            ClaimRepository claims) {
        this.audit = audit;
        this.claims = claims;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(), LocalDate.now()));
            } catch (DataIntegrityViolationException e) {
                // another user started the same sheet at the same moment
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /** The sheet's today, for any date-dependent decision or default date on it. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(LocalDate::now);
    }

    /** The sheet as the store holds it now; a sheet never loaded reads as a new one, today the real date. */
    @Transactional(readOnly = true)
    public Sheet sheet(String id) {
        return sheets.findById(id).orElseGet(() -> new Sheet(id, null, null, LocalDate.now()));
    }

    /** The sheet's clerk limit in force on its today: the most a clerk may settle a claim's due at; null for no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String id) {
        return sheets.findById(id).map(Sheet::getClerkLimit).orElse(null);
    }

    /** A person's due-total held to the sheet's cap in force on its today (none: as it is; n/a stays n/a). */
    @Transactional(readOnly = true)
    public BigDecimal capped(String id, BigDecimal dueTotal) {
        return sheets.findById(id).map(s -> s.capped(dueTotal)).orElse(dueTotal);
    }

    /** Refuse (423) any change to an archived sheet: it stays visible and may not be changed. */
    // a refusal leaves the caller's transaction usable (a bulk action refuses one claim and goes on)
    @Transactional(readOnly = true, noRollbackFor = ResponseStatusException.class)
    public void refuseIfArchived(String id) {
        if (sheet(id).isArchived()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "This sheet is archived and may not be changed.");
        }
    }

    /** Archive the sheet; refused (409) when it is already archived. The caller checks the user is a steward. */
    @Transactional
    public Sheet archive(String id, String user) {
        load(id, user);
        Sheet sheet = lock(id);
        if (sheet.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This sheet is already archived.");
        }
        sheet.archive();
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + user);
        return saved;
    }

    /** Lock the sheet until the caller's transaction ends; refused (404) when there is no such sheet. */
    @Transactional
    public Sheet lock(String id) {
        return sheets.findLockedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
    }

    /**
     * The next number for a claim dated {@code date} opened on the sheet, with no gaps and never
     * reused. Sheet-wide (1, 2, ...) unless the sheet numbers by period on its today: then within
     * the period the date falls in ({@code <period name>-<n>}), or in no period its X sequence
     * ({@code X-<n>}), each counting from 1. The sheet stays locked until the caller's transaction
     * ends, so two claims opened at the same moment take different numbers.
     */
    @Transactional
    public String nextClaimNumber(String id, LocalDate date) {
        Sheet sheet = lock(id);
        if (!sheet.numbersByPeriod()) {
            int number = sheet.nextClaimNumber();
            sheets.saveAndFlush(sheet);
            return String.valueOf(number);
        }
        Optional<Period> period = periods.findBySheetIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(id, date,
                date);
        if (period.isPresent()) {
            int number = period.get().nextClaimNumber();
            periods.saveAndFlush(period.get());
            return period.get().getName() + "-" + number;
        }
        int number = sheet.nextUnperiodClaimNumber();
        sheets.saveAndFlush(sheet);
        return "X-" + number;
    }

    /**
     * Close an open period, fixing its due-total now, and record it ({@code auto=yes} when closed by
     * a change of the sheet's today). The caller holds the sheet.
     */
    @Transactional
    public Period closePeriod(String id, String user, Period period, boolean auto) {
        period.close(rollup.dueBetweenByPerson(id, period.getFromDate(), period.getToDate()));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + id + " by=" + user + " period=" + saved.getName() + " total="
                + saved.getClosedDue().setScale(2, RoundingMode.UNNECESSARY).toPlainString() + (auto ? " auto=yes" : ""));
        return saved;
    }

    /** Soft-close an open period, fixing its due-total now, and record it. The caller holds the sheet. */
    @Transactional
    public Period softClosePeriod(String id, String user, Period period) {
        period.softClose(rollup.dueBetweenByPerson(id, period.getFromDate(), period.getToDate()));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + id + " by=" + user + " period=" + saved.getName()
                + " total=" + saved.getClosedDue().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        return saved;
    }

    /**
     * Change the sheet's today. With grace days set, every open (not soft-closed) period whose to-date
     * plus the grace days is before the new today is then closed, in order of their to-dates. A new today releases
     * every claim editor's lock. A new today first records each person's due-total on the day being left (SNAPSHOT),
     * in list order.
     */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        refuseIfArchived(id);
        load(id, user);
        // hold the sheet so no claim changes while periods close
        Sheet sheet = lock(id);
        RollupService.Snapshot before = rollup.snapshot(id);
        boolean moved = !today.equals(sheet.getToday());
        if (moved) {
            // each person's due-total as it stood on the day being left, before anything else the change records
            for (RollupService.Figures figures : before.people().values()) {
                audit.recordOnCommit("SNAPSHOT sheet=" + id + " by=" + user + " name=" + figures.name()
                        + " due-total=" + (figures.dueTotal() == null ? "n/a"
                                : figures.dueTotal().setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
            }
        }
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        Integer grace = saved.getGraceDays();
        if (grace != null) {
            for (Period period : periods.findBySheetIdAndClosedFalseOrderByToDateAsc(id)) {
                // a soft-closed period keeps the figures it fixed: only an open one closes by grace days
                if (!period.isSoftClosed() && period.getToDate().plusDays(grace).isBefore(today)) {
                    closePeriod(id, user, period, true);
                }
            }
        }
        // changing today has no audit line of its own, only the periods it closes and the figures it moves
        rollup.recordChanges(id, user, before);
        if (moved) {
            claims.unlockAll(id);
        }
        return saved;
    }
}
