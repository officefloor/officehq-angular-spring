package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates for each grade on a sheet, each applying from its from-date. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetService sheets;
    private final Rollup rollup;
    private final Audit audit;

    public RateService(RateRepository rates, SheetService sheets, Rollup rollup, Audit audit) {
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The rates set on the sheet, by grade and then from-date. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrdered(sheetId);
    }

    /** Set the rate for a grade from a date (null: from the beginning of time), replacing the one
     *  the grade had for that from-date. */
    @Transactional
    public synchronized Rate set(String sheetId, String user, String grade, BigDecimal pct, LocalDate from) {
        sheets.load(sheetId, user);
        return rollup.tracking(sheetId, user, () -> {
            Rate rate = (from == null
                    ? rates.findBySheetIdAndGradeAndFromDateIsNull(sheetId, grade)
                    : rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from))
                    .orElseGet(() -> new Rate(sheetId, grade, pct, from));
            rate.setPct(pct);
            Rate saved = rates.saveAndFlush(rate);
            audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade
                    + " rate=" + saved.getPct().setScale(2).toPlainString()
                    + (from != null ? " from=" + from : ""));
            return saved;
        });
    }
}
