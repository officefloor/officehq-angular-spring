package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
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

    public SheetService(SheetRepository sheets, RollupService rollup, PeriodRepository periods) {
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

    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        RollupService.Snapshot before = rollup.snapshot(id);
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        // changing today has no audit line of its own, only the figures it moves
        rollup.recordChanges(id, user, before);
        return saved;
    }
}
