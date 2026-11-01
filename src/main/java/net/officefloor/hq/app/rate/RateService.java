package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sheet's rate for each grade: set one grade at a time, replacing any rate it had. */
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
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) throw new SheetNotFoundException();
        Rate rate = rates.findBySheetIdAndGrade(sheetId, grade).orElseGet(() -> new Rate(sheetId, grade, pct));
        rate.setPct(pct);
        Rate saved = rates.saveAndFlush(rate);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user.strip()
                + " grade=" + saved.getGrade() + " rate=" + saved.getPct().setScale(2).toPlainString());
        return saved;
    }
}
