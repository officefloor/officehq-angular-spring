package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
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
    private final Audit audit;

    public RateService(RateRepository rates, SheetRepository sheets, Audit audit) {
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId);
    }

    /** The grade's rate on the date: the one with the latest from-date on or before it, if any. */
    @Transactional(readOnly = true)
    public Optional<Rate> on(String sheetId, String grade, LocalDate date) {
        return rates.findBySheetIdAndGrade(sheetId, grade).stream()
                .filter(rate -> rate.appliesOn(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, BigDecimal pct) {
        // Lock the sheet so two users setting the same grade and from-date at once replace one rate.
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Optional<Rate> existing = from == null
                ? rates.findBySheetIdAndGradeAndFromDateIsNull(sheetId, grade)
                : rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from);
        Rate rate = existing.orElseGet(() -> new Rate(sheetId, grade, from, pct));
        rate.setPct(pct);
        Rate saved = rates.saveAndFlush(rate);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user.strip()
                + " grade=" + saved.getGrade() + " rate=" + saved.getPct().setScale(2).toPlainString()
                + (saved.getFromDate() != null ? " from=" + saved.getFromDate() : ""));
        return saved;
    }
}
