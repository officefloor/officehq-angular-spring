package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates for the grades on a sheet: listed and set. */
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

    /** The sheet's rates by grade. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** Set the rate for a grade, replacing any it had. */
    @Transactional
    public synchronized Rate set(String sheetId, String user, String grade, BigDecimal pct) {
        sheets.load(sheetId);
        BigDecimal percent = pct.setScale(2, RoundingMode.UNNECESSARY);
        Rate rate = rates.findBySheetIdAndGrade(sheetId, grade).orElseGet(() -> new Rate(sheetId, grade, percent));
        rate.setPct(percent);
        Rate saved = rates.saveAndFlush(rate);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + saved.getGrade() + " rate=" + saved.getPct().toPlainString());
        return saved;
    }
}
