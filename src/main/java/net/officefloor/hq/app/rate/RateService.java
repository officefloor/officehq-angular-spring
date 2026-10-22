package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The rates for the grades on a sheet: listed and set. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetService sheets;
    private final Rollups rollups;
    private final Audit audit;

    public RateService(RateRepository rates, SheetService sheets, Rollups rollups, Audit audit) {
        this.rates = rates;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's rates by grade. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId);
    }

    /**
     * Set the rate for a grade from a date (null: from the beginning of time), replacing any the
     * grade had from that same date, applying through a to-date (null: no end); a to-date before the
     * from-date is refused.
     */
    @Transactional
    public synchronized Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to,
            BigDecimal pct) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rate's to-date cannot be before its from-date.");
        }
        sheets.load(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        BigDecimal percent = pct.setScale(2, RoundingMode.UNNECESSARY);
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from)
                .orElseGet(() -> new Rate(sheetId, grade, from, percent));
        rate.setPct(percent);
        rate.setToDate(to);
        Rate saved = rates.saveAndFlush(rate);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + saved.getGrade() + " rate=" + saved.getPct().toPlainString()
                + (saved.getFromDate() != null ? " from=" + saved.getFromDate() : "")
                + (saved.getToDate() != null ? " to=" + saved.getToDate() : ""));
        rollups.audit(sheetId, user, before);
        return saved;
    }
}
