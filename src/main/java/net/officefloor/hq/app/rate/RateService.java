package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates set on a sheet for each grade, each in force from its from-date. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetService sheets;
    private final Audit audit;

    public RateService(RateRepository rates, SheetService sheets, Audit audit) {
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's rates by grade, each grade's from the earliest. */
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromAscIdAsc(sheetId);
    }

    /** The grade's rate in force on a date: the one whose from-date is the latest on or before it
     *  (a rate with no from-date applies from the beginning of time); empty when none applies. */
    public Optional<Rate> rateOn(String sheetId, String grade, LocalDate date) {
        return rates.findBySheetIdAndGradeOrderByFromAsc(sheetId, grade).stream()
                .filter(rate -> rate.appliesOn(date))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /** Set the rate for a grade from a date (null: from the beginning of time), replacing the one
     *  the grade had from that date. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, BigDecimal rate) {
        sheets.load(sheetId);
        BigDecimal scaled = rate.setScale(2, RoundingMode.UNNECESSARY);
        Optional<Rate> had = from == null
                ? rates.findBySheetIdAndGradeAndFromIsNull(sheetId, grade)
                : rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from);
        Rate saved = had
                .map(existing -> {
                    existing.setRate(scaled);
                    return existing;
                })
                .orElseGet(() -> new Rate(sheetId, grade, from, scaled));
        saved = rates.saveAndFlush(saved);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade
                + " rate=" + saved.getRate().toPlainString() + (from != null ? " from=" + from : ""));
        return saved;
    }
}
