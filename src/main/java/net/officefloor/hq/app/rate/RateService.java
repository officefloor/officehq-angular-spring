package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Grade;
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

    /** The sheet's rates, one per grade that has one. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** Set a grade's rate, replacing the one it had (the sheet is started when it was never loaded before). */
    @Transactional
    public Rate set(String sheetId, String user, Grade grade, BigDecimal pct) {
        sheets.load(sheetId, user);
        Rate rate = rates.findBySheetIdAndGrade(sheetId, grade).orElseGet(() -> new Rate(sheetId, grade, pct));
        rate.setPct(pct);
        Rate saved = rates.saveAndFlush(rate);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + saved.getGrade() + " rate=" + saved.getPct().setScale(2).toPlainString());
        return saved;
    }
}
