package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the rate for each grade on a sheet. */
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

    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** Set the grade's rate, replacing the one it had. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        Rate rate = rates.findBySheetIdAndGrade(sheetId, grade).orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, pct);
        } else {
            rate.setPct(pct);
        }
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException e) {
            throw new RateTakenException(sheetId, grade);
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + saved.getGrade()
                + " rate=" + saved.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        return saved;
    }
}
