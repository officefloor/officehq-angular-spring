package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's rates for each grade, each from a date or from the beginning of time.
 *  perhaps through a to-date. Setting a grade's rate for a from-date it already has replaces that
 *  rate, and its to-date. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetRepository sheets;
    private final Rollup rollup;
    private final Audit audit;

    public RateService(RateRepository rates, SheetRepository sheets, Rollup rollup, Audit audit) {
        this.rollup = rollup;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        requireSheet(sheetId);
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        requireSheet(sheetId);
        if (to != null && from != null && to.isBefore(from)) {
            throw new RateRefusedException("A rate's to-date cannot be before its from-date.");
        }
        BigDecimal value = pct.setScale(2);
        Rollup.Snapshot before = rollup.take(sheetId);
        Rate rate = rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from).orElse(null);
        if (rate == null) {
            try {
                rate = rates.saveAndFlush(new Rate(sheetId, grade, from, to, value));
            } catch (DataIntegrityViolationException e) {
                // Another user set the same grade and from-date's first rate at the same moment.
                throw new RateConflictException();
            }
        } else {
            rate.setRate(value);
            rate.setToDate(to);
            rate = rates.saveAndFlush(rate);
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + grade + " rate=" + rate.getRate().toPlainString()
                + (from == null ? "" : " from=" + from)
                + (to == null ? "" : " to=" + to));
        rollup.recordChanges(sheetId, user, before);
        return rate;
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
    }
}
