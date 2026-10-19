package net.officefloor.hq.app.setting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.rollup.RollupService.Snapshot;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A sheet's settings: the role each user name has on it, its clerk limit, its floor, its terms, its late percent, its policy date, its period numbering date and its cap. */
@Service
public class SettingService {

    private final SheetRoleRepository roles;
    private final SheetRepository sheetRepository;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public SettingService(SheetRoleRepository roles, SheetRepository sheetRepository, SheetService sheets,
            RollupService rollup, Audit audit) {
        this.roles = roles;
        this.sheetRepository = sheetRepository;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The roles given on the sheet. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return roles.findBySheetIdOrderById(sheetId);
    }

    /** The role a user name has on the sheet: a clerk unless given another. */
    @Transactional(readOnly = true)
    public Role roleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(Role.CLERK);
    }

    /** Give a user name a role on the sheet, replacing any it had. */
    @Transactional
    public SheetRole setRole(String user, String sheetId, String name, Role role) {
        sheets.lock(sheetId);
        SheetRole given = roles.findBySheetIdAndUser(sheetId, name).orElse(null);
        if (given == null) {
            given = roles.saveAndFlush(new SheetRole(sheetId, name, role));
        } else {
            given.setRole(role);
            roles.flush();
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + user + " user=" + name + " role=" + role.code());
        return given;
    }

    /** Save the settings given, the clerk limit, the floor, the terms, each grade's terms (in the order given),
     * the late percent then the policy date, the period numbering date and the cap together; a setting not given
     * (null) is left as it is, and an empty clerk limit, grade's terms, policy date, period numbering date or cap
     * removes it. */
    @Transactional
    public void save(String user, String sheetId, Optional<BigDecimal> clerkLimit, BigDecimal floor,
            Integer terms, Map<String, Optional<Integer>> gradeTerms, BigDecimal latePct,
            Optional<LocalDate> policyDate, Optional<LocalDate> periodNumberingFrom, Optional<BigDecimal> cap) {
        if (clerkLimit != null) {
            setClerkLimit(user, sheetId, clerkLimit.orElse(null));
        }
        if (floor != null) {
            setFloor(user, sheetId, floor);
        }
        if (terms != null) {
            setTerms(user, sheetId, terms);
        }
        gradeTerms.forEach((grade, days) -> setGradeTerms(user, sheetId, grade, days.orElse(null)));
        if (latePct != null) {
            setLatePct(user, sheetId, latePct);
        }
        if (policyDate != null) {
            setPolicyDate(user, sheetId, policyDate.orElse(null));
        }
        if (periodNumberingFrom != null) {
            setPeriodNumberingFrom(user, sheetId, periodNumberingFrom.orElse(null));
        }
        if (cap != null) {
            setCap(user, sheetId, cap.orElse(null));
        }
    }

    /** The sheet's clerk limit; none means no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return find(sheetId).getClerkLimit();
    }

    /** Set the sheet's clerk limit; none removes it. */
    @Transactional
    public BigDecimal setClerkLimit(String user, String sheetId, BigDecimal limit) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        BigDecimal value = limit != null ? limit.setScale(2) : null;
        sheet.setClerkLimit(value);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=clerk-limit value="
                + (value != null ? value.toPlainString() : ""));
        return value;
    }

    /** The sheet's floor: the least a claim's live due comes to. */
    @Transactional(readOnly = true)
    public BigDecimal floor(String sheetId) {
        return find(sheetId).getFloor();
    }

    /** Set the sheet's floor; it moves every live due below it, and the figures it moves are written after. */
    @Transactional
    public BigDecimal setFloor(String user, String sheetId, BigDecimal floor) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        Snapshot before = rollup.snapshot(sheetId);
        BigDecimal value = floor.setScale(2);
        sheet.setFloor(value);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=floor value="
                + value.toPlainString());
        rollup.recordChanges(user, sheetId, before);
        return value;
    }

    /** The sheet's terms: the days after its date a claim is due by. */
    @Transactional(readOnly = true)
    public int terms(String sheetId) {
        return find(sheetId).getTerms();
    }

    /** Set the sheet's terms; they go for claims added from now on (a claim's due-by is fixed when it is added),
     * so they move no figure. */
    @Transactional
    public int setTerms(String user, String sheetId, int terms) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        sheet.setTerms(terms);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=terms value=" + terms);
        return terms;
    }

    /** A grade's own terms; none when it uses the sheet's. */
    @Transactional(readOnly = true)
    public Integer gradeTerms(String sheetId, String grade) {
        return find(sheetId).getGradeTerms(grade);
    }

    /** Set a grade's own terms, none to have it use the sheet's; like the sheet's terms they go for claims added
     * from now on. */
    @Transactional
    public Integer setGradeTerms(String user, String sheetId, String grade, Integer terms) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        sheet.setGradeTerms(grade, terms);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=terms-" + grade + " value="
                + (terms != null ? terms.toString() : ""));
        return terms;
    }

    /** The terms in force for a claim of a person of the grade: the grade's own, else the sheet's. */
    @Transactional(readOnly = true)
    public int termsFor(String sheetId, String grade) {
        return find(sheetId).termsFor(grade);
    }

    /** The sheet's late percent: what an overdue claim gains of its figure for every full 30 days overdue. */
    @Transactional(readOnly = true)
    public BigDecimal latePct(String sheetId) {
        return find(sheetId).getLatePct();
    }

    /** Set the sheet's late percent; it moves every late charge, and the figures it moves are written after. */
    @Transactional
    public BigDecimal setLatePct(String user, String sheetId, BigDecimal latePct) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        Snapshot before = rollup.snapshot(sheetId);
        BigDecimal value = latePct.setScale(2);
        sheet.setLatePct(value);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=late-pct value="
                + value.toPlainString());
        rollup.recordChanges(user, sheetId, before);
        return value;
    }

    /** The sheet's policy date: a claim dated before it rounds half up, one dated on or after it half to even;
     * none means the sheet's today. */
    @Transactional(readOnly = true)
    public LocalDate policyDate(String sheetId) {
        return find(sheetId).getPolicyDate();
    }

    /** Set the sheet's policy date, none for the sheet's today; it moves how live figures round, and the figures
     * it moves are written after. */
    @Transactional
    public LocalDate setPolicyDate(String user, String sheetId, LocalDate policyDate) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        Snapshot before = rollup.snapshot(sheetId);
        sheet.setPolicyDate(policyDate);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=policy-date value="
                + (policyDate != null ? policyDate.toString() : ""));
        rollup.recordChanges(user, sheetId, before);
        return policyDate;
    }

    /** The sheet's period numbering date: while the sheet's today is on or after it, a claim opened is numbered
     * within its period; none means off. */
    @Transactional(readOnly = true)
    public LocalDate periodNumberingFrom(String sheetId) {
        return find(sheetId).getPeriodNumberingFrom();
    }

    /** Set the sheet's period numbering date, none to turn it off; it numbers only claims opened from now on. */
    @Transactional
    public LocalDate setPeriodNumberingFrom(String user, String sheetId, LocalDate from) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        sheet.setPeriodNumberingFrom(from);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=period-numbering-from value="
                + (from != null ? from.toString() : ""));
        return from;
    }

    /** The sheet's cap: the most a person's due-total comes to; none means no cap. */
    @Transactional(readOnly = true)
    public BigDecimal cap(String sheetId) {
        return find(sheetId).getCap();
    }

    /** Set the sheet's cap, none to remove it; it moves every due-total above it, and the figures it moves are
     * written after. */
    @Transactional
    public BigDecimal setCap(String user, String sheetId, BigDecimal cap) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        Snapshot before = rollup.snapshot(sheetId);
        BigDecimal value = cap != null ? cap.setScale(2) : null;
        sheet.setCap(value);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=cap value="
                + (value != null ? value.toPlainString() : ""));
        rollup.recordChanges(user, sheetId, before);
        return value;
    }

    /** Whether a user may settle a claim of this due outright: a due of n/a is within any limit, and a steward
     * settles anything; a clerk's settle above the clerk limit goes to a steward instead. */
    @Transactional(readOnly = true)
    public boolean maySettle(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = clerkLimit(sheetId);
        return limit == null || due == null || due.compareTo(limit) <= 0 || roleOf(sheetId, user) == Role.STEWARD;
    }

    /** Refuse a change only a steward may make, such as approving or rejecting a PENDING claim. */
    @Transactional(readOnly = true)
    public void requireSteward(String sheetId, String user) {
        if (roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Only a steward can do that.");
        }
    }

    private Sheet find(String sheetId) {
        return sheetRepository.findById(sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
    }
}
