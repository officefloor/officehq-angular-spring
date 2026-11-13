package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rate set on a sheet for each grade. */
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
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** Set the rate for a grade, replacing the one it had. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, BigDecimal rate) {
        sheets.load(sheetId);
        BigDecimal scaled = rate.setScale(2, RoundingMode.UNNECESSARY);
        Rate saved = rates.findBySheetIdAndGrade(sheetId, grade)
                .map(existing -> {
                    existing.setRate(scaled);
                    return existing;
                })
                .orElseGet(() -> new Rate(sheetId, grade, scaled));
        saved = rates.saveAndFlush(saved);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade
                + " rate=" + saved.getRate().toPlainString());
        return saved;
    }
}
