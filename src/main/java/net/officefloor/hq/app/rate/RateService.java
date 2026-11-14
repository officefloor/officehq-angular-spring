package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    /** Set a grade's rate on the sheet, replacing the rate it had (the sheet is created first if it was never loaded). */
    @Transactional
    public Rate set(String sheetId, String user, String grade, BigDecimal pct) {
        sheets.load(sheetId, user);
        BigDecimal scaled = pct.setScale(2);
        Rate rate = rates.findBySheetIdAndGrade(sheetId, grade).orElse(null);
        try {
            if (rate == null) {
                rate = rates.saveAndFlush(new Rate(sheetId, grade, scaled));
            } else {
                rate.setPct(scaled);
                rates.flush();
            }
        } catch (DataIntegrityViolationException e) {
            // set by another user at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rate changed at the same moment");
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + grade + " rate=" + scaled.toPlainString());
        return rate;
    }
}
