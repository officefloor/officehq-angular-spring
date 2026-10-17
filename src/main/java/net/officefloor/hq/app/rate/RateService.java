package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rate for each grade on a sheet. Setting a grade's rate replaces any rate it had. */
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

    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, BigDecimal pct) {
        sheets.load(sheetId, user);
        BigDecimal scaled = pct.setScale(2, RoundingMode.UNNECESSARY);
        Rate rate = rates.findBySheetIdAndGrade(sheetId, grade).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, scaled));
        } else {
            rate.setRate(scaled);
        }
        audit.change("RATE_SET", sheetId, user, "grade=" + rate.getGrade(), "rate=" + rate.getRate().toPlainString());
        return rate;
    }
}
