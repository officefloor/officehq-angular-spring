package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
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
    private final RollupService rollup;
    private final Audit audit;

    public RateService(RateRepository rates, SheetService sheets, RollupService rollup, Audit audit) {
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAsc(sheetId);
    }

    /** Set a grade's rate from a date (null: from the beginning of time) through a date (null: for
     *  good) on the sheet, replacing the rate it had for that from-date (the sheet is created first if it was never loaded). */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        sheets.load(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = pct.setScale(2);
        Rate rate = rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from).orElse(null);
        try {
            if (rate == null) {
                rate = rates.saveAndFlush(new Rate(sheetId, grade, from, to, scaled));
            } else {
                rate.setPct(scaled);
                rate.setTo(to);
                rates.flush();
            }
        } catch (DataIntegrityViolationException e) {
            // set by another user at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rate changed at the same moment");
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + grade + " rate=" + scaled.toPlainString() + (from != null ? " from=" + from : "")
                + (to != null ? " to=" + to : ""));
        rollup.recordChanges(sheetId, user, before);
        return rate;
    }
}
