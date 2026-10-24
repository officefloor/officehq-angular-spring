package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A sheet's settings: the role each user name has on it (a clerk unless made a steward) and its clerk
 * limit, the most a clerk may settle a claim for (none when null), and its floor, the least a claim's
 * live due can be, and its terms, how many days after its date a claim is due by, and its late percent, the
 * percent of its figure an overdue claim's due gains for every full 30 days it is overdue, and its policy
 * date, which decides how a claim's live figures round (the sheet's today when none is set), and its
 * period numbering from, the date from which a claim opened is numbered within its period (off when null). */
@Service
public class SettingsService {

    private final SheetRepository sheets;
    private final SheetRoleRepository roles;
    private final RollupService rollup;
    private final Audit audit;

    public SettingsService(SheetRepository sheets, SheetRoleRepository roles, RollupService rollup, Audit audit) {
        this.sheets = sheets;
        this.roles = roles;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The roles given on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUser(sheetId);
    }

    /** The user's role on the sheet: a clerk unless made something else. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    @Transactional
    public SheetRole setRole(String sheetId, String by, String user, Role role) {
        if (!sheets.existsById(sheetId)) throw new SheetNotFoundException();
        SheetRole given = roles.findBySheetIdAndUser(sheetId, user).orElse(null);
        if (given == null) {
            given = roles.saveAndFlush(new SheetRole(sheetId, user, role));
        } else {
            given.setRole(role);
            roles.flush();
        }
        audit.change("ROLE_SET", sheetId, by, "user=" + user, "role=" + role.name().toLowerCase());
        return given;
    }

    /** The sheet's clerk limit; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getClerkLimit();
    }

    /** Set the sheet's clerk limit; null removes it. */
    @Transactional
    public Sheet setClerkLimit(String sheetId, String by, BigDecimal limit) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setClerkLimit(limit);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=clerk-limit",
                "value=" + (limit == null ? "" : limit.toPlainString()));
        return sheet;
    }

    /** The sheet's floor. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getFloor).orElseThrow(SheetNotFoundException::new);
    }

    /** Set the sheet's floor; the live dues, and so the figures, follow it. */
    @Transactional
    public Sheet setFloor(String sheetId, String by, BigDecimal floor) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setFloor(floor);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=floor", "value=" + floor.toPlainString());
        rollup.recordSince(sheetId, by, before);
        return sheet;
    }

    /** The sheet's terms, in days. */
    @Transactional(readOnly = true)
    public int terms(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getTerms).orElseThrow(SheetNotFoundException::new);
    }

    /** Set the sheet's terms; the claims' due-by dates follow them. */
    @Transactional
    public Sheet setTerms(String sheetId, String by, int terms) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setTerms(terms);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=terms", "value=" + terms);
        return sheet;
    }

    /** The sheet's late percent. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getLatePct).orElseThrow(SheetNotFoundException::new);
    }

    /** Set the sheet's late percent; the late charges, and so the figures, follow it. */
    @Transactional
    public Sheet setLatePct(String sheetId, String by, BigDecimal latePct) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setLatePct(latePct);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=late-pct", "value=" + latePct.toPlainString());
        rollup.recordSince(sheetId, by, before);
        return sheet;
    }

    /** The sheet's policy date as set; null when it is the sheet's today. */
    @Transactional(readOnly = true)
    public LocalDate policyDate(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getPolicyDate();
    }

    /** Set the sheet's policy date; null makes it the sheet's today. The live figures' rounding follows it. */
    @Transactional
    public Sheet setPolicyDate(String sheetId, String by, LocalDate policyDate) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setPolicyDate(policyDate);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=policy-date",
                "value=" + (policyDate == null ? "" : policyDate.toString()));
        rollup.recordSince(sheetId, by, before);
        return sheet;
    }

    /** The sheet's period numbering from; null when it is off. */
    @Transactional(readOnly = true)
    public LocalDate periodNumberingFrom(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getPeriodNumberingFrom();
    }

    /** Set the sheet's period numbering from; null turns it off. Only the claims opened from then on follow it. */
    @Transactional
    public Sheet setPeriodNumberingFrom(String sheetId, String by, LocalDate from) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        sheet.setPeriodNumberingFrom(from);
        sheets.flush();
        audit.change("SETTING_CHANGED", sheetId, by, "key=period-numbering-from",
                "value=" + (from == null ? "" : from.toString()));
        return sheet;
    }
}
