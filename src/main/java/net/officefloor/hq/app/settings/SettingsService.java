package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sheet's settings (its clerk limit, floor, terms, terms per grade, late percent, policy date and period numbering from-date) and the roles it gives user names. */
@Service
public class SettingsService {

    private final SheetRoleRepository roles;
    private final SheetService sheets;
    private final RollupService rollups;
    private final Audit audit;

    public SettingsService(SheetRoleRepository roles, SheetService sheets, RollupService rollups, Audit audit) {
        this.roles = roles;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's settings now. */
    @Transactional
    public Sheet settings(String sheetId, String user) {
        return sheets.load(sheetId, user);
    }

    /**
     * Save the settings given: {@code setClerkLimit} the clerk limit (null: no limit), a non-null
     * {@code floor} the floor, a non-null {@code terms} the terms in days, each grade in {@code gradeTerms}
     * its own terms in days (null: it uses the sheet's terms), a non-null {@code latePct} the
     * late percent, {@code setPolicyDate} the policy date (null: the sheet's today), {@code setPeriodNumberingFrom}
     * the period numbering from-date (null: off); each one saved is
     * audited, even when its value is the same. A floor, terms, late percent or policy date that moves
     * dues records the rollups that moved.
     */
    @Transactional
    public Sheet save(String sheetId, String user, boolean setClerkLimit, BigDecimal clerkLimit, BigDecimal floor,
            Integer terms, Map<Grade, Integer> gradeTerms, BigDecimal latePct, boolean setPolicyDate, LocalDate policyDate,
            boolean setPeriodNumberingFrom, LocalDate periodNumberingFrom) {
        Sheet sheet = sheets.lock(sheetId, user);
        if (setClerkLimit) {
            sheet.setClerkLimit(clerkLimit);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                    + " key=clerk-limit value=" + (clerkLimit == null ? "" : clerkLimit.toPlainString()));
        }
        if (floor != null) {
            RollupService.Rollup before = rollups.snapshot(sheetId);
            sheet.setFloor(floor);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                    + " key=floor value=" + floor.toPlainString());
            rollups.recordChanges(sheetId, user, before);
        }
        if (terms != null) {
            RollupService.Rollup before = rollups.snapshot(sheetId);
            sheet.setTerms(terms);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                    + " key=terms value=" + terms);
            rollups.recordChanges(sheetId, user, before);
        }
        for (Grade grade : Grade.values()) {
            if (!gradeTerms.containsKey(grade)) continue;
            Integer days = gradeTerms.get(grade);
            sheet.setGradeTerms(grade, days);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                    + " key=terms-" + grade + " value=" + (days == null ? "" : days));
        }
        if (latePct != null) {
            RollupService.Rollup before = rollups.snapshot(sheetId);
            sheet.setLatePct(latePct);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                    + " key=late-pct value=" + latePct.toPlainString());
            rollups.recordChanges(sheetId, user, before);
        }
        if (setPolicyDate) {
            RollupService.Rollup before = rollups.snapshot(sheetId);
            sheet.setPolicyDate(policyDate);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                    + " key=policy-date value=" + (policyDate == null ? "" : policyDate));
            rollups.recordChanges(sheetId, user, before);
        }
        if (setPeriodNumberingFrom) {
            sheet.setPeriodNumberingFrom(periodNumberingFrom);
            audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user
                    + " key=period-numbering-from value=" + (periodNumberingFrom == null ? "" : periodNumberingFrom));
        }
        return sheet;
    }

    /** The roles the sheet gives user names, by name; a name not among them is a clerk. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserNameAsc(sheetId);
    }

    /** The role the sheet gives a user name; clerk when it gives none. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String userName) {
        return roles.findBySheetIdAndUserName(sheetId, userName).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** Give a user name a role, replacing the one it had. */
    @Transactional
    public SheetRole setRole(String sheetId, String user, String userName, Role role) {
        sheets.lock(sheetId, user);
        SheetRole entry = roles.findBySheetIdAndUserName(sheetId, userName)
                .orElseGet(() -> new SheetRole(sheetId, userName, role));
        entry.setRole(role);
        SheetRole saved = roles.saveAndFlush(entry);
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user
                + " user=" + userName + " role=" + role.name().toLowerCase());
        return saved;
    }
}
