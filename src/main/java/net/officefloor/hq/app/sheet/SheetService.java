package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet that was never loaded before starts empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    /** Lazy: the rollup reads the sheet's today through this service. */
    private final Rollup rollup;
    private final Audit audit;

    public SheetService(SheetRepository sheets, @Lazy Rollup rollup, Audit audit) {
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet with this id, created empty the first time it is loaded, its today the real date. */
    @Transactional
    public synchronized Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> sheets.save(new Sheet(id, user, LocalDate.now())));
    }

    /** The sheet's today: the date every date-dependent decision and default date on it uses. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(LocalDate::now);
    }

    /** The next claim number on the sheet: 1 for its first opened claim, then 2, and so on. */
    @Transactional
    public int nextClaimNumber(String id) {
        if (sheets.advanceClaimNumber(id) != 1) {
            throw new IllegalStateException("No sheet " + id);
        }
        return sheets.lastClaimNumber(id);
    }

    /** Change the sheet's today; the figures it moves are recorded, with no record of its own. */
    @Transactional
    public synchronized Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setToday(today);
            return sheets.saveAndFlush(sheet);
        });
    }

    /** The most due a clerk may settle on a claim on the sheet; null when there is no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String id) {
        return sheets.findById(id).map(Sheet::getClerkLimit).orElse(null);
    }

    /** Change the sheet's clerk limit (null: no limit); recorded even when it is unchanged. */
    @Transactional
    public synchronized Sheet setClerkLimit(String id, String user, BigDecimal limit) {
        Sheet sheet = load(id, user);
        sheet.setClerkLimit(limit);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=clerk-limit value="
                + (limit == null ? "" : limit.setScale(2).toPlainString()));
        return saved;
    }

    /** The least live due a claim on the sheet has; 1.00 for a sheet never loaded. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String id) {
        return sheets.findById(id).map(Sheet::getFloor).orElse(new BigDecimal("1.00"));
    }

    /** Change the sheet's floor; recorded even when it is unchanged, then the figures it moves. */
    @Transactional
    public synchronized Sheet setFloor(String id, String user, BigDecimal floor) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setFloor(floor);
            Sheet saved = sheets.saveAndFlush(sheet);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=floor value="
                    + floor.setScale(2).toPlainString());
            return saved;
        });
    }

    /** The days after its date a claim on the sheet is due by; 14 for a sheet never loaded. */
    @Transactional(readOnly = true)
    public int terms(String id) {
        return sheets.findById(id).map(Sheet::getTerms).orElse(14);
    }

    /** Change the sheet's terms; recorded even when they are unchanged, then the figures they move
     *  (through the late charges of overdue claims). */
    @Transactional
    public synchronized Sheet setTerms(String id, String user, int terms) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setTerms(terms);
            Sheet saved = sheets.saveAndFlush(sheet);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=terms value=" + terms);
            return saved;
        });
    }

    /** The percent of its figure an overdue claim on the sheet gains for every full 30 days it is
     *  overdue; 0.00 for a sheet never loaded. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String id) {
        return sheets.findById(id).map(Sheet::getLatePct).orElse(new BigDecimal("0.00"));
    }

    /** Change the sheet's late percent; recorded even when it is unchanged, then the figures it moves. */
    @Transactional
    public synchronized Sheet setLatePct(String id, String user, BigDecimal latePct) {
        Sheet sheet = load(id, user);
        return rollup.tracking(id, user, () -> {
            sheet.setLatePct(latePct);
            Sheet saved = sheets.saveAndFlush(sheet);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=late-pct value="
                    + latePct.setScale(2).toPlainString());
            return saved;
        });
    }
}
