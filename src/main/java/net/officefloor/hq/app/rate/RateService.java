package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Grade;
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
    private final Rollup rollup;
    private final Audit audit;

    public RateService(RateRepository rates, RateChangeRepository changes, SheetService sheets,
            SheetParentRepository parents, Rollup rollup, Audit audit) {
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

    /** A rate of the sheet or of one of its parents, with how many levels up it is (0: the sheet's own). */
    public record Inherited(int level, Rate rate) {
    }

    /** The rates of the sheet and of its parents up to three levels up, nearest first, each in grade order,
     *  then by from-date. */
    @Transactional(readOnly = true)
    public List<Inherited> listInherited(String sheetId) {
        List<Inherited> all = new ArrayList<>();
        List<String> lineage = SettingService.lineageOf(parents, sheetId);
        for (int level = 0; level < lineage.size(); level++) {
            for (Rate rate : rates.findBySheetIdInOrder(lineage.get(level))) all.add(new Inherited(level, rate));
        }
        return all;
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

    /** The grade's rate in force on a date as the sheet's rates stood at the end of another date (asOf):
     *  every setting made while the sheet's today was on or before it counts, every later one does not;
     *  null when none is in force then. */
    @Transactional(readOnly = true)
    public Rate inForceAsOf(String sheetId, Grade grade, LocalDate date, LocalDate asOf) {
        // The parents are those the sheet names now, each read as its rates stood at the end of the same date.
        for (String id : SettingService.lineageOf(parents, sheetId)) {
            Rate rate = ownInForceAsOf(id, grade, date, asOf);
            if (rate != null) return rate;
        }
        return null;
    }

    private Rate ownInForceAsOf(String sheetId, Grade grade, LocalDate date, LocalDate asOf) {
        Map<LocalDate, Rate> byFrom = new HashMap<>();
        Rate fromStart = null;
        for (RateChange change : changes.findBySheetIdOrderByIdAsc(sheetId)) {
            if (change.getGrade() != grade || !change.madeBy(asOf)) continue;
            if (change.getFrom() == null) fromStart = change.toRate();
            else byFrom.put(change.getFrom(), change.toRate());
        }
        List<Rate> gradeRates = new ArrayList<>();
        if (fromStart != null) gradeRates.add(fromStart);
        byFrom.keySet().stream().sorted().forEach((from) -> gradeRates.add(byFrom.get(from)));
        return Rate.inForce(gradeRates, date);
    }

    /** Set the sheet's rate for a grade from a from-date (none: from the beginning of time) through
     *  a to-date (none: until the grade's next rate), replacing the rate it had for that from-date. A
     *  to-date before the from-date is refused. */
    @Transactional
    public Rate set(String sheetId, String user, Grade grade, LocalDate from, LocalDate to, BigDecimal pct) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date.");
        }
        sheets.load(sheetId);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Rate rate = (from == null ? rates.findFromStart(sheetId, grade) : rates.findFrom(sheetId, grade, from))
                .orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, from, to, pct);
        } else {
            rate.setPct(pct);
            rate.setTo(to);
        }
        Rate saved;
        try {
            saved = rates.saveAndFlush(rate);
            changes.saveAndFlush(new RateChange(saved, sheets.today(sheetId)));
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
