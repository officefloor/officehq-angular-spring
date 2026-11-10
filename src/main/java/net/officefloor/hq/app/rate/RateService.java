package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sheet's rates for each grade: set (or set again) on the screen, one per grade and from-date;
 * setting a grade's rate for a from-date it already has replaces that rate (and its to-date). A
 * to-date before the from-date is refused.
 */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final RoleService roles;

    public RateService(RateRepository rates, SheetService sheets, Audit audit, RollupService rollup, RoleService roles) {
        this.roles = roles;
        this.rollup = rollup;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromDateAsc(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate fromDate, LocalDate toDate,
            BigDecimal pct) {
        roles.refuseViewer(sheetId, user);
        if (toDate != null && fromDate != null && toDate.isBefore(fromDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rate's to-date cannot be before its from-date.");
        }
        sheets.load(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Rate rate = rates.findBySheetIdAndGradeAndFrom(sheetId, grade, fromDate)
                .orElseGet(() -> new Rate(sheetId, grade, fromDate, pct));
        rate.setPct(pct);
        rate.setToDate(toDate);
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException e) {
            // another user set this grade's rate from this date at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This rate was just set by someone else.");
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade + " rate="
                + saved.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + (fromDate != null ? " from=" + fromDate : "") + (toDate != null ? " to=" + toDate : ""));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }
}
