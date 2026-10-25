package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Grade;
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

    /** The sheet's rates in grade order; a grade with no rate is left out. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** Set the sheet's rate for a grade, replacing the rate it had. */
    @Transactional
    public Rate set(String sheetId, String user, Grade grade, BigDecimal pct) {
        sheets.load(sheetId);
        Rate rate = rates.findById(new Rate.Key(sheetId, grade)).orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, pct);
        } else {
            rate.setPct(pct);
        }
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException raced) {
            // Another user set the same grade's first rate at the same moment.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The rate was changed by someone else.");
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + saved.getGrade()
                + " rate=" + saved.getPct().toPlainString());
        return saved;
    }
}
