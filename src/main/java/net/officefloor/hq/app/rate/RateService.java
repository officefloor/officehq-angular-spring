package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's rates for each grade, each from a date (or from the beginning of time): set one grade
 * and from-date at a time, replacing any rate it had. A date's rate is the one with the latest
 * from-date on or before it.
 */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetRepository sheets;
    private final RollupService rollup;
    private final Audit audit;

    public RateService(RateRepository rates, SheetRepository sheets, RollupService rollup, Audit audit) {
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId);
    }

    /** The grade's rate on the date: the one with the latest from-date on or before it, if any and not ended. */
    @Transactional(readOnly = true)
    public Optional<Rate> on(String sheetId, String grade, LocalDate date) {
        return Rate.inForce(rates.findBySheetIdAndGrade(sheetId, grade), date);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        if (to != null && from != null && to.isBefore(from)) throw new RateDatesException();
        // Lock the sheet so two users setting the same grade and from-date at once replace one rate.
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Optional<Rate> existing = from == null
                ? rates.findBySheetIdAndGradeAndFromDateIsNull(sheetId, grade)
                : rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from);
        Rate rate = existing.orElseGet(() -> new Rate(sheetId, grade, from, to, pct));
        rate.setPct(pct);
        rate.setToDate(to);
        Rate saved = rates.saveAndFlush(rate);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user.strip()
                + " grade=" + saved.getGrade() + " rate=" + saved.getPct().setScale(2).toPlainString()
                + (saved.getFromDate() != null ? " from=" + saved.getFromDate() : "")
                + (saved.getToDate() != null ? " to=" + saved.getToDate() : ""));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }
}
