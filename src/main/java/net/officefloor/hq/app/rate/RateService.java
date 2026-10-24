package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rate each grade has on each sheet. Setting a grade's rate replaces the one it had. */
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

    /** The sheet's rates, by grade. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        Rate rate = rates.findBySheetIdAndGrade(sheetId, grade).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, pct));
        } else {
            rate.setPct(pct);
            rates.flush();
        }
        audit.change("RATE_SET", sheetId, user, "grade=" + grade,
                "rate=" + rate.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        return rate;
    }
}
