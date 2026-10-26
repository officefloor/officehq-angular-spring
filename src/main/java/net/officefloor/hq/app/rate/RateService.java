package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.person.SheetGradeRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.setting.SheetParentRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RateService {

    private final RateRepository rates;
    private final RateChangeRepository changes;
    private final SheetService sheets;
    private final SheetParentRepository parents;
    private final ClaimRepository claims;
    private final Rollup rollup;
    private final Audit audit;
    private final SheetGradeRepository grades;

    public RateService(RateRepository rates, RateChangeRepository changes, SheetService sheets,
            SheetParentRepository parents, ClaimRepository claims, Rollup rollup, Audit audit, SheetGradeRepository grades) {
        this.grades = grades;
        this.claims = claims;
        this.rates = rates;
        this.changes = changes;
        this.sheets = sheets;
        this.parents = parents;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's rates in grade order, then by from-date; a grade with no rate is left out. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdInOrder(sheetId);
    }

    /** A rate of the sheet or of one of its parents, with how many levels up it is (0: the sheet's own), and
     *  when its newest version is forward-only, its older versions, newest first, down to the newest that applies
     *  to every claim. */
    public record Inherited(int level, Rate rate, List<RateChange> older) {
    }

    /** The rates of the sheet and of its parents up to three levels up, nearest first, each in grade order,
     *  then by from-date. */
    @Transactional(readOnly = true)
    public List<Inherited> listInherited(String sheetId) {
        List<Inherited> all = new ArrayList<>();
        List<String> lineage = SettingService.lineageOf(parents, sheetId);
        AppliedRates applied = AppliedRates.of(changes, lineage);
        for (int level = 0; level < lineage.size(); level++) {
            for (Rate rate : rates.findBySheetIdInOrder(lineage.get(level))) {
                all.add(new Inherited(level, rate, applied.olderVersions(rate)));
            }
        }
        return all;
    }

    /** The rate's older versions, newest first, down to the newest that applies to every claim; none when
     *  its newest does. */
    @Transactional(readOnly = true)
    public List<RateChange> olderVersions(Rate rate) {
        if (rate.getForwardAfter() == null) return List.of();
        return AppliedRates.of(changes, List.of(rate.getSheetId())).olderVersions(rate);
    }

    /** The grade's rate in force on a date: of the sheet's rates, the one with the latest from-date on or
     *  before it, unless the date is after that rate's to-date; when none is, the parent's in force then, and
     *  so on up to three levels up; null when none is. */
    @Transactional(readOnly = true)
    public Rate inForce(String sheetId, Grade grade, LocalDate date) {
        List<List<Rate>> levels = new ArrayList<>();
        for (String id : SettingService.lineageOf(parents, sheetId)) levels.add(rates.findForGrade(id, grade));
        return Rate.inForceInherited(levels, date);
    }

    /** The grade's rate in force on a date for a claim, as {@link #inForce} tells it of the versions of the rates
     *  that apply to the claim: a forward-only version only to claims added after it was saved. */
    @Transactional(readOnly = true)
    public Rate inForceFor(String sheetId, Grade grade, LocalDate date, Long claimId) {
        List<String> lineage = SettingService.lineageOf(parents, sheetId);
        List<List<Rate>> levels = new ArrayList<>();
        for (String id : lineage) levels.add(rates.findForGrade(id, grade));
        if (levels.stream().flatMap(List::stream).allMatch((r) -> r.appliesToClaim(claimId))) {
            return Rate.inForceInherited(levels, date);
        }
        return Rate.inForceInherited(AppliedRates.of(changes, lineage).appliedLevels(levels, claimId), date);
    }

    /** The grade's rate in force on a date as the sheet's rates stood at the end of another date (asOf):
     *  every setting made while the sheet's today was on or before it counts, every later one does not;
     *  null when none is in force then. */
    @Transactional(readOnly = true)
    public Rate inForceAsOf(String sheetId, Grade grade, LocalDate date, LocalDate asOf) {
        return inForceAsOf(sheetId, grade, date, asOf, null);
    }

    /** The grade's rate in force on a date for a claim as the sheet's rates stood at the end of another date
     *  (see {@link #inForceAsOf(String, Grade, LocalDate, LocalDate)}), of the versions that apply to the claim:
     *  a forward-only version only to claims added after it was saved. */
    @Transactional(readOnly = true)
    public Rate inForceAsOf(String sheetId, Grade grade, LocalDate date, LocalDate asOf, Long claimId) {
        // The parents are those the sheet names now, each read as its rates stood at the end of the same date.
        for (String id : SettingService.lineageOf(parents, sheetId)) {
            Rate rate = ownInForceAsOf(id, grade, date, asOf, claimId);
            if (rate != null) return rate;
        }
        return null;
    }

    private Rate ownInForceAsOf(String sheetId, Grade grade, LocalDate date, LocalDate asOf, Long claimId) {
        Map<LocalDate, Rate> byFrom = new HashMap<>();
        Rate fromStart = null;
        for (RateChange change : changes.findBySheetIdOrderByIdAsc(sheetId)) {
            if (change.getGrade() != grade || !change.madeBy(asOf) || !change.appliesToClaim(claimId)) continue;
            if (change.getFrom() == null) fromStart = change.toRate();
            else byFrom.put(change.getFrom(), change.toRate());
        }
        List<Rate> gradeRates = new ArrayList<>();
        if (fromStart != null) gradeRates.add(fromStart);
        byFrom.keySet().stream().sorted().forEach((from) -> gradeRates.add(byFrom.get(from)));
        return Rate.inForce(gradeRates, date);
    }

    /** Set the sheet's rate for a grade from a from-date (none: from the beginning of time) through
     *  a to-date (none: until the grade's next rate). When the grade already has a rate for that from-date, the
     *  value it held is kept as an older version and the new one becomes its newest version, the one in force. A
     *  to-date before the from-date is refused. A rate saved with a from-date on or after the sheet's today is
     *  forward-only: that version applies only to the claims added after it is saved. */
    @Transactional
    public Rate set(String sheetId, String user, Grade grade, LocalDate from, LocalDate to, BigDecimal pct) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date.");
        }
        sheets.load(sheetId);
        if (!grades.isGradeOf(sheetId, grade)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "There is no grade " + grade + ".");
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheets.today(sheetId);
        Long forwardAfter = from != null && today != null && !from.isBefore(today) ? claims.maxId() : null;
        Rate rate = (from == null ? rates.findFromStart(sheetId, grade) : rates.findFrom(sheetId, grade, from))
                .orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, from, to, pct, forwardAfter);
        } else {
            rate.newVersion(pct, to, forwardAfter);
        }
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
            changes.saveAndFlush(new RateChange(saved, today));
        } catch (DataIntegrityViolationException raced) {
            // Another user set the same grade's rate for that from-date at the same moment.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The rate was changed by someone else.");
        }
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + saved.getGrade()
                + " rate=" + saved.getPct().toPlainString() + (from == null ? "" : " from=" + from)
                + (to == null ? "" : " to=" + to));
        rollup.recordChanges(user, before);
        return saved;
    }
}
