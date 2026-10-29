package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/** The rate set for each grade on a sheet. */
@Service
public class RateService {

    private final RateRepository rates;
    private final RateChangeRepository changes;
    private final RateVersionRepository versions;
    private final RateChain chain;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public RateService(RateRepository rates, RateChangeRepository changes,
            RateVersionRepository versions, RateChain chain, SheetService sheets,
            RollupService rollup, Audit audit) {
        this.rates = rates;
        this.changes = changes;
        this.versions = versions;
        this.chain = chain;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The rates set on the sheet, by grade and then from-date (no from-date first). */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromAsc(sheetId);
    }

    /** The rates that apply on the sheet, its parent sheets' filling in for it (see {@link RateChain}). */
    @Transactional(readOnly = true)
    public List<Rate> applying(String sheetId) {
        return chain.applying(sheetId);
    }

    /** The rates that apply on the sheet by the claim mark they apply to (see {@link RateChain#tiers}). */
    @Transactional(readOnly = true)
    public List<RateChain.Tier> tiers(String sheetId) {
        return chain.tiers(sheetId);
    }

    /**
     * Sets the grade's rate from the date (null: from the beginning of time) through the to-date (null: with no end),
     * as a new version of any rate it had for that from-date, the one it replaces kept as an older version. Set with a
     * from-date on or after the sheet's today, the version is forward-only: it applies only to claims added after it
     * is saved, the claims already on the sheet keeping the rates that applied to them (see {@link Rate#appliesTo}).
     */
    @Transactional
    public Rate set(String by, String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        if (!sheets.load(sheetId).isGrade(grade)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No such grade: " + grade + ".");
        }
        LocalDate today = sheets.load(sheetId).getToday();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = pct.setScale(2, RoundingMode.UNNECESSARY);
        Long afterMark = from != null && !from.isBefore(today) ? rates.nextClaimMark() : null;
        Rate rate = rates.findExact(sheetId, grade, from).orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, from, scaled);
            rate.setTo(to);
            rate.setAfterMark(afterMark);
        } else {
            versions.save(new RateVersion(rate));
            rate.newVersion(scaled, to, afterMark);
        }
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException e) {
            // another user set this grade's rate for this from-date at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That rate was just set by someone else.");
        }
        changes.saveAndFlush(new RateChange(saved, today));
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + by + " grade=" + grade
                + " rate=" + saved.getPct().toPlainString() + (from != null ? " from=" + from : "")
                + (to != null ? " to=" + to : ""));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }
}
