package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
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

    /** The sheet's rates by grade; a grade without one has no rate. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** Set the rate for a grade, replacing any it had. */
    @Transactional
    public Rate set(String user, String sheetId, String grade, BigDecimal pct) {
        sheets.today(sheetId); // the sheet must exist
        BigDecimal value = pct.setScale(2);
        Rate rate = rates.findById(new Rate.Key(sheetId, grade)).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, value));
        } else {
            rate.setRate(value);
            rates.flush();
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade
                + " rate=" + value.toPlainString());
        return rate;
    }
}
