package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** The sheet's rates by grade, then from-date; a grade without one has no rate. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return rates.findBySheetId(sheetId).stream()
                .sorted(Comparator.comparing(Rate::getGrade).thenComparing(Rate.BY_FROM))
                .toList();
    }

    /** Set the rate for a grade from a from-date (none: from the beginning of time), replacing any it had
     * from that date. */
    @Transactional
    public Rate set(String user, String sheetId, String grade, LocalDate from, BigDecimal pct) {
        sheets.today(sheetId); // the sheet must exist
        BigDecimal value = pct.setScale(2);
        Rate rate = rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, from, value));
        } else {
            rate.setRate(value);
            rates.flush();
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade
                + " rate=" + value.toPlainString() + (from != null ? " from=" + from : ""));
        return rate;
    }
}
