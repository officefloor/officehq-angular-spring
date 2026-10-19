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

/** A sheet's settings: the role each user name has on it, its clerk limit, its floor, its terms, its late percent, its policy date, its period numbering date and its cap; those that carry a figure are dated. */
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
     * the late percent then the policy date, the period numbering date, the cap and the proof threshold together; a
     * setting not given (null) is left as it is, and an empty clerk limit, grade's terms, policy date, period
     * numbering date, cap or proof threshold removes it. A setting that carries a figure is recorded from its from-date in {@code froms}, by key, or
     * from the sheet's today when none is given. */
    @Transactional
    public void save(String user, String sheetId, Optional<BigDecimal> clerkLimit, BigDecimal floor,
            Integer terms, Map<String, Optional<Integer>> gradeTerms, BigDecimal latePct,
            Optional<LocalDate> policyDate, Optional<LocalDate> periodNumberingFrom, Optional<BigDecimal> cap,
            Optional<BigDecimal> proofThreshold, Map<String, LocalDate> froms) {
        if (clerkLimit != null) {
            setClerkLimit(user, sheetId, clerkLimit.orElse(null), froms.get(Sheet.CLERK_LIMIT));
        }
        if (floor != null) {
            setFloor(user, sheetId, floor, froms.get(Sheet.FLOOR));
        }
        if (terms != null) {
            setTerms(user, sheetId, terms, froms.get(Sheet.TERMS));
        }
        gradeTerms.forEach((grade, days) -> setGradeTerms(user, sheetId, grade, days.orElse(null),
                froms.get(Sheet.gradeTermsKey(grade))));
        if (latePct != null) {
            setLatePct(user, sheetId, latePct, froms.get(Sheet.LATE_PCT));
        }
        if (policyDate != null) {
            setPolicyDate(user, sheetId, policyDate.orElse(null));
        }
        if (periodNumberingFrom != null) {
            setPeriodNumberingFrom(user, sheetId, periodNumberingFrom.orElse(null));
        }
        if (cap != null) {
            setCap(user, sheetId, cap.orElse(null), froms.get(Sheet.CAP));
        }
        if (proofThreshold != null) {
            setProofThreshold(user, sheetId, proofThreshold.orElse(null), froms.get(Sheet.PROOF_THRESHOLD));
        }
    }

    /** The sheet, for the values its dated settings have. */
    @Transactional(readOnly = true)
    public Sheet sheet(String sheetId) {
        return find(sheetId);
    }

    /** The sheet's clerk limit in force on its today; none means no limit. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String sheetId) {
        return find(sheetId).getClerkLimit();
    }

    /** Set the sheet's clerk limit from a date (the sheet's today when none); none removes it from then. */
    @Transactional
    public BigDecimal setClerkLimit(String user, String sheetId, BigDecimal limit, LocalDate from) {
        BigDecimal value = limit != null ? limit.setScale(2) : null;
        record(user, sheetId, Sheet.CLERK_LIMIT, from, value, value != null ? value.toPlainString() : "", false);
        return value;
    }

    /** Set the sheet's floor from a date (the sheet's today when none); it moves every live due below it of a
     * claim dated from then until a later one, and the figures it moves are written after. */
    @Transactional
    public BigDecimal setFloor(String user, String sheetId, BigDecimal floor, LocalDate from) {
        BigDecimal value = floor.setScale(2);
        record(user, sheetId, Sheet.FLOOR, from, value, value.toPlainString(), true);
        return value;
    }

    /** Set the sheet's terms from a date (the sheet's today when none); they go for claims added from now on
     * dated from then (a claim's due-by is fixed when it is added), so they move no figure. */
    @Transactional
    public int setTerms(String user, String sheetId, int terms, LocalDate from) {
        record(user, sheetId, Sheet.TERMS, from, BigDecimal.valueOf(terms), String.valueOf(terms), false);
        return terms;
    }

    /** Set a grade's own terms from a date (the sheet's today when none), none to have it use the sheet's from
     * then; like the sheet's terms they go for claims added from now on. */
    @Transactional
    public Integer setGradeTerms(String user, String sheetId, String grade, Integer terms, LocalDate from) {
        record(user, sheetId, Sheet.gradeTermsKey(grade), from, terms != null ? BigDecimal.valueOf(terms) : null,
                terms != null ? terms.toString() : "", false);
        return terms;
    }

    /** The terms in force for a claim of a person of the grade dated on a date: the grade's own, else the
     * sheet's. */
    @Transactional(readOnly = true)
    public int termsFor(String sheetId, String grade, LocalDate date) {
        return find(sheetId).termsFor(grade, date);
    }

    /** Set the sheet's late percent from a date (the sheet's today when none); it moves the late charge of every
     * claim dated from then until a later one, and the figures it moves are written after. */
    @Transactional
    public BigDecimal setLatePct(String user, String sheetId, BigDecimal latePct, LocalDate from) {
        BigDecimal value = latePct.setScale(2);
        record(user, sheetId, Sheet.LATE_PCT, from, value, value.toPlainString(), true);
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

    /** Set the sheet's cap from a date (the sheet's today when none), none to remove it from then; it moves
     * every due-total above the cap in force on the sheet's today, and the figures it moves are written after. */
    @Transactional
    public BigDecimal setCap(String user, String sheetId, BigDecimal cap, LocalDate from) {
        BigDecimal value = cap != null ? cap.setScale(2) : null;
        record(user, sheetId, Sheet.CAP, from, value, value != null ? value.toPlainString() : "", true);
        return value;
    }

    /** Set the sheet's proof threshold from a date (the sheet's today when none), none to remove it from then; it
     * moves no figure, only whether a claim dated from then needs a proof to be settled or approved. */
    @Transactional
    public BigDecimal setProofThreshold(String user, String sheetId, BigDecimal threshold, LocalDate from) {
        BigDecimal value = threshold != null ? threshold.setScale(2) : null;
        record(user, sheetId, Sheet.PROOF_THRESHOLD, from, value, value != null ? value.toPlainString() : "",
                false);
        return value;
    }

    /** Refuse settling or approving a claim dated on a date, with this due and proofs count, when its due is
     * above the proof threshold in force on its date and it has no proof; a due of n/a is above no threshold. */
    @Transactional(readOnly = true)
    public void requireProof(String sheetId, LocalDate date, BigDecimal due, int proofs) {
        BigDecimal threshold = find(sheetId).proofThresholdOn(date);
        if (threshold != null && due != null && due.compareTo(threshold) > 0 && proofs == 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "A claim with a due above " + threshold.setScale(2).toPlainString() + " needs a proof first.");
        }
    }

    /** Record a dated setting's value from a date, the sheet's today when none is given, replacing one recorded
     * from that date; the audit line carries the from-date only when one was given. When {@code movesFigures},
     * the figures it moves are written after. */
    private void record(String user, String sheetId, String key, LocalDate from, BigDecimal value, String shown,
            boolean movesFigures) {
        sheets.lock(sheetId);
        Sheet sheet = find(sheetId);
        Snapshot before = movesFigures ? rollup.snapshot(sheetId) : null;
        sheet.record(key, from != null ? from : sheet.getToday(), value);
        sheetRepository.flush();
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + user + " key=" + key + " value=" + shown
                + (from != null ? " from=" + from : ""));
        if (movesFigures) {
            rollup.recordChanges(user, sheetId, before);
        }
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
