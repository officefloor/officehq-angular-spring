package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.rollup.Rollup;
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
    private final Rollup rollup;
    private final Audit audit;

    public RateService(RateRepository rates, SheetService sheets, Rollup rollup, Audit audit) {
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's rates in grade order, then by from-date; a grade with no rate is left out. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdInOrder(sheetId);
    }

    /** The grade's rate in force on a date: the one with the latest from-date on or before it, or
     *  null when the date is before every rate the grade has. */
    @Transactional(readOnly = true)
    public Rate inForce(String sheetId, Grade grade, LocalDate date) {
        Rate inForce = null;
        for (Rate rate : rates.findForGrade(sheetId, grade)) {
            if (rate.appliesBy(date)) inForce = rate;
        }
        return inForce;
    }

    /** Set the sheet's rate for a grade from a from-date (none: from the beginning of time),
     *  replacing the rate it had for that from-date. */
    @Transactional
    public Rate set(String sheetId, String user, Grade grade, LocalDate from, BigDecimal pct) {
        sheets.load(sheetId);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Rate rate = (from == null ? rates.findFromStart(sheetId, grade) : rates.findFrom(sheetId, grade, from))
                .orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, from, pct);
        } else {
            rate.setPct(pct);
        }
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException raced) {
            // Another user set the same grade's rate for that from-date at the same moment.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The rate was changed by someone else.");
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + saved.getGrade()
                + " rate=" + saved.getPct().toPlainString() + (from == null ? "" : " from=" + from));
        rollup.recordChanges(user, before);
        return saved;
    }
}
