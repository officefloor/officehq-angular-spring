package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
 * setting a grade's rate for a from-date it already has keeps the rate it replaces as an older
 * version and makes the new value (and its to-date) the newest, the one in force. A to-date before
 * the from-date is refused. A rate saved with a from-date on or after the sheet's today is
 * forward-only: it applies only to claims added after it was saved (see {@link Rate#appliesTo}).
 */
@Service
public class RateService {

    private final RateRepository rates;
    private final RateLogRepository rateLog;
    private final RateVersionRepository versions;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final RoleService roles;
    private final RateChain chain;

    public RateService(RateRepository rates, RateLogRepository rateLog, RateVersionRepository versions,
            SheetService sheets, Audit audit,
            RollupService rollup, RoleService roles, RateChain chain) {
        this.chain = chain;
        this.rateLog = rateLog;
        this.versions = versions;
        this.roles = roles;
        this.rollup = rollup;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's own rates, then those of each of its ancestors, nearest first (see {@link RateChain}). */
    @Transactional(readOnly = true)
    public List<List<Rate>> list(String sheetId) {
        return chain.current(sheetId);
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
        Optional<Rate> replaced = rates.findBySheetIdAndGradeAndFrom(sheetId, grade, fromDate);
        replaced.ifPresent(old -> {
            versions.save(new RateVersion(old));
            old.setVersion(old.getVersion() + 1);
        });
        Rate rate = replaced.orElseGet(() -> new Rate(sheetId, grade, fromDate, pct));
        rate.setPct(pct);
        rate.setToDate(toDate);
        LocalDate today = sheets.today(sheetId);
        rate.setForwardAfter(fromDate != null && today != null && !fromDate.isBefore(today) ? rates.nextEntrySeq() : null);
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException e) {
            // another user set this grade's rate from this date at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This rate was just set by someone else.");
        }
        rateLog.saveAndFlush(new RateLog(saved, today));
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade + " rate="
                + saved.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + (fromDate != null ? " from=" + fromDate : "") + (toDate != null ? " to=" + toDate : ""));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }
}
