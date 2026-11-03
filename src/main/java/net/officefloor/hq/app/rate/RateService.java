package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's rates for each grade: set (or set again) on the screen, one per grade and from-date;
 * setting a grade's rate for a from-date it already has replaces that rate.
 */
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
        return rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate fromDate, BigDecimal pct) {
        sheets.load(sheetId, user);
        Rate rate = rates.findBySheetIdAndGradeAndFrom(sheetId, grade, fromDate)
                .orElseGet(() -> new Rate(sheetId, grade, fromDate, pct));
        rate.setPct(pct);
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException e) {
            // another user set this grade's rate from this date at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This rate was just set by someone else.");
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade + " rate="
                + saved.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + (fromDate != null ? " from=" + fromDate : ""));
        return saved;
    }
}
