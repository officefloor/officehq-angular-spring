package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the rates for each grade on a sheet, each in force from its from-date. */
@Service
public class RateService {

    private final RateRepository rates;
    private final RateLogRepository logs;
    private final RateVersionRepository versions;
    private final ClaimRepository claims;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;

    public RateService(RateRepository rates, RateLogRepository logs, RateVersionRepository versions,
            ClaimRepository claims, SheetRepository sheets, Audit audit, RollupService rollup) {
        this.claims = claims;
        this.rates = rates;
        this.logs = logs;
        this.versions = versions;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /**
     * The sheet's rates, then each parent sheet's up to {@link RollupService#PARENT_LEVELS} levels up (nearest first),
     * each in grade order then by from-date.
     */
    public List<List<Rate>> chain(String sheetId) {
        return rollup.rateChain(sheetId);
    }

    /**
     * The grade's rate in force on the date: the one with the latest from-date on or before it, an
     * undated rate counting as from the beginning of time. Empty when the date is before every rate, or
     * after that rate's to-date. With none on the sheet, the parent sheet's, then its parent's, up to three levels up.
     */
    public Optional<Rate> rateOn(String sheetId, String grade, LocalDate date) {
        return RollupService.rateOn(rollup.rateChain(sheetId), grade, date);
    }

    /**
     * As {@link #rateOn(String, String, LocalDate)}, for the claim with the stamp: a forward-only rate saved before it
     * was added is left out, falling back on its newest older version the claim sees.
     */
    public Optional<Rate> rateOn(String sheetId, String grade, LocalDate date, Long stamp) {
        return RollupService.rateOn(rollup.rateChain(sheetId), grade, date, stamp);
    }

    /**
     * The grade's rate percent in force on the date as the rates stood at the end of {@code asOf}: each from-date's
     * last setting made while the sheet's today was on or before it (a rate set before settings were logged counts as
     * it is now). With none on the sheet then, the parent sheets' rates in force on the date as they stood then.
     * Empty when the grade had no rate in force on the date then.
     */
    public Optional<BigDecimal> rateAsOf(String sheetId, String grade, LocalDate date, LocalDate asOf) {
        return rateAsOf(sheetId, grade, date, asOf, null);
    }

    /**
     * As {@link #rateAsOf(String, String, LocalDate, LocalDate)}, for the claim with the stamp: a forward-only setting
     * made before it was added is left out, falling back on the setting before it the claim sees.
     */
    public Optional<BigDecimal> rateAsOf(String sheetId, String grade, LocalDate date, LocalDate asOf, Long stamp) {
        List<Rate> stood = RateLog.stood(sheetId, grade, rollup.withOlder(rates.findBySheetIdAndGrade(sheetId, grade)),
                logs.findBySheetIdAndGradeOrderByIdAsc(sheetId, grade), asOf);
        Optional<BigDecimal> own = stood.stream()
                .flatMap(r -> r.seenBy(stamp).stream())
                .filter(r -> r.getFromDate() == null || !r.getFromDate().isAfter(date))
                .max(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(r -> r.appliesOn(date))
                .map(Rate::getPct);
        if (own.isPresent()) {
            return own;
        }
        List<List<Rate>> chain = rollup.rateChain(sheetId, asOf);
        return RollupService.rateOn(chain.subList(1, chain.size()), grade, date, stamp).map(Rate::getPct);
    }

    /** What the grade having the percent on every date would change on the sheet; nothing is saved. */
    @Transactional(readOnly = true)
    public RollupService.RatePreview preview(String sheetId, String grade, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        return rollup.preview(sheetId, grade, pct);
    }

    /**
     * Set the grade's rate from the from-date (null: from the beginning of time) through the to-date (null: no
     * end). A rate the grade already has from the from-date is kept as an older version and the new one becomes its
     * newest, the one in force. A to-date before the from-date is refused. A rate with a from-date on or after the
     * sheet's today is forward-only: only claims added after it is saved see it; the claims already there keep the rates
     * they saw (this one's older version, if any, among them).
     */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate fromDate, LocalDate toDate, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw new RateDatesException(fromDate, toDate);
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheets.findById(sheetId).orElseThrow().getToday();
        Long forwardAfter = fromDate != null && today != null && !fromDate.isBefore(today) ? claims.maxId() : null;
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, fromDate).orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, fromDate, pct);
        } else {
            versions.save(new RateVersion(rate));
            rate.setVersion(rate.getVersion() + 1);
            rate.setPct(pct);
        }
        rate.setToDate(toDate);
        rate.setForwardAfter(forwardAfter);
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
        } catch (DataIntegrityViolationException e) {
            throw new RateTakenException(sheetId, grade);
        }
        logs.save(new RateLog(saved, today));
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + saved.getGrade()
                + " rate=" + saved.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + (saved.getFromDate() == null ? "" : " from=" + saved.getFromDate())
                + (saved.getToDate() == null ? "" : " to=" + saved.getToDate()));
        rollup.recordChanges(sheetId, user, before);
        rollup.withOlder(List.of(saved));
        return saved;
    }
}
