package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rate set for each grade on a sheet. */
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

    /** The rates set on the sheet, by grade. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** Set a grade's rate, replacing any rate it had. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, BigDecimal rate) {
        sheets.load(sheetId, user);
        BigDecimal scaled = rate.setScale(2, RoundingMode.UNNECESSARY);
        Rate row = rates.findBySheetIdAndGrade(sheetId, grade).orElseGet(() -> new Rate(sheetId, grade, scaled));
        row.setRate(scaled);
        Rate saved = rates.saveAndFlush(row);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + saved.getGrade() + " rate=" + saved.getRate().toPlainString());
        return saved;
    }
}
