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

    /** The rates set on the sheet, by grade and then from-date (no from-date first). */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromAsc(sheetId);
    }

    /** Sets the grade's rate from the date (null: from the beginning of time), replacing any rate it had for it. */
    @Transactional
    public Rate set(String by, String sheetId, String grade, LocalDate from, BigDecimal pct) {
        sheets.load(sheetId);
        BigDecimal scaled = pct.setScale(2, RoundingMode.UNNECESSARY);
        Rate rate = rates.findExact(sheetId, grade, from).orElseGet(() -> new Rate(sheetId, grade, from, scaled));
        rate.setPct(scaled);
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException e) {
            // another user set this grade's rate for this from-date at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That rate was just set by someone else.");
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + by + " grade=" + grade
                + " rate=" + saved.getPct().toPlainString() + (from != null ? " from=" + from : ""));
        return saved;
    }
}
