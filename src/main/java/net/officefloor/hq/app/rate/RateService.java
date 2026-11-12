package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps each sheet's rates for each grade, each from a date or from the beginning of time.
 *  Setting a grade's rate for a from-date it already has replaces that rate. */
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
        requireSheet(sheetId);
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, BigDecimal pct) {
        requireSheet(sheetId);
        BigDecimal value = pct.setScale(2);
        Rate rate = rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from).orElse(null);
        if (rate == null) {
            try {
                rate = rates.saveAndFlush(new Rate(sheetId, grade, from, value));
            } catch (DataIntegrityViolationException e) {
                // Another user set the same grade and from-date's first rate at the same moment.
                throw new RateConflictException();
            }
        } else {
            rate.setRate(value);
            rate = rates.saveAndFlush(rate);
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + grade + " rate=" + rate.getRate().toPlainString()
                + (from == null ? "" : " from=" + from));
        return rate;
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
    }
}
