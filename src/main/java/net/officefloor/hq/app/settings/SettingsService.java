package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A sheet's settings: the role each user name has; the clerk limit, the floor, the terms (the sheet's and each
 * grade's), the late percent and the cap, each recorded from a from-date; the policy date and the period numbering
 * from; the grace days; the permission matrix, the roles that may take each action; and the delegations of the
 * steward role. It tells the audit for whom a delegated user acts.
 */
@Service
public class SettingsService implements Audit.OnBehalf {

    /** The actions of the permission matrix, with the roles that may take each while none are set. */
    public static final Map<String, List<String>> STANDING_PERMISSIONS = Map.of(
            "open", List.of(SheetRole.CLERK, SheetRole.STEWARD),
            "settle", List.of(SheetRole.CLERK, SheetRole.STEWARD),
            "void", List.of(SheetRole.CLERK, SheetRole.STEWARD),
            "reassign", List.of(SheetRole.STEWARD),
            "close", List.of(SheetRole.CLERK, SheetRole.STEWARD),
            "reopen", List.of(SheetRole.STEWARD));

    private final SheetRoleRepository roles;
    private final DelegationRepository delegations;
    private final SheetRepository sheetRepository;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public SettingsService(SheetRoleRepository roles, DelegationRepository delegations,
            SheetRepository sheetRepository, SheetService sheets, RollupService rollup, Audit audit) {
        this.roles = roles;
        this.delegations = delegations;
        this.sheetRepository = sheetRepository;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The roles set on the sheet, by user name. */
    @Transactional(readOnly = true)
    public List<SheetRole> roles(String sheetId) {
        return roles.findBySheetIdOrderByUserAsc(sheetId);
    }

    /**
     * The role the user acts in on the sheet: steward while a steward's delegation to them covers the sheet's today,
     * else the role set for them, clerk unless set otherwise.
     */
    @Transactional(readOnly = true)
    public String roleOf(String sheetId, String user) {
        String set = setRoleOf(sheetId, user);
        if (!SheetRole.STEWARD.equals(set) && delegatorOf(sheetId, user).isPresent()) {
            return SheetRole.STEWARD;
        }
        return set;
    }

    /** The role set for the user on the sheet, leaving delegations aside: clerk unless set otherwise. */
    private String setRoleOf(String sheetId, String user) {
        return roles.findBySheetIdAndUser(sheetId, user).map(SheetRole::getRole).orElse(SheetRole.CLERK);
    }

    /**
     * The steward the user acts for on the sheet: the one whose delegation to them, made first, covers the sheet's
     * today and who is still a steward; empty when there is none.
     */
    @Transactional(readOnly = true)
    public Optional<String> delegatorOf(String sheetId, String user) {
        List<Delegation> made = delegations.findBySheetIdAndUserOrderByIdAsc(sheetId, user);
        if (made.isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = sheets.load(sheetId).getToday();
        return made.stream().filter(d -> d.covers(today))
                .filter(d -> SheetRole.STEWARD.equals(setRoleOf(sheetId, d.getDelegatedBy())))
                .map(Delegation::getDelegatedBy).findFirst();
    }

    @Override
    public Optional<String> forOf(String sheetId, String user) {
        return delegatorOf(sheetId, user);
    }

    /** The delegations made on the sheet, in the order they were made. */
    @Transactional(readOnly = true)
    public List<Delegation> delegations(String sheetId) {
        return delegations.findBySheetIdOrderByIdAsc(sheetId);
    }

    /**
     * Delegates the steward role to the user from the from-date to the to-date, inclusive (each the sheet's today
     * when null). Refused unless the one delegating has the steward role set (not delegated), or when the to-date
     * is before the from-date.
     */
    @Transactional
    public Delegation delegate(String by, String sheetId, String user, LocalDate from, LocalDate to) {
        Sheet sheet = sheets.lock(sheetId);
        if (!SheetRole.STEWARD.equals(setRoleOf(sheetId, by))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can delegate the steward role.");
        }
        LocalDate start = from == null ? sheet.getToday() : from;
        LocalDate end = to == null ? sheet.getToday() : to;
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The to-date cannot be before the from-date.");
        }
        Delegation saved = delegations.saveAndFlush(new Delegation(sheetId, user, by, start, end));
        audit.recordOnCommit("DELEGATED sheet=" + sheetId + " by=" + by + " user=" + user + " from=" + start
                + " to=" + end);
        return saved;
    }

    /** Archives the sheet, after which every change on it is refused. Refused unless the one archiving is a steward. */
    @Transactional
    public Sheet archive(String by, String sheetId) {
        Sheet sheet = sheets.lock(sheetId);
        if (!isSteward(sheetId, by)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can archive the sheet.");
        }
        sheet.archive();
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + sheetId + " by=" + by);
        return saved;
    }

    /** Sets the user's role on the sheet; refused unless it is clerk, steward or viewer. */
    @Transactional
    public SheetRole setRole(String by, String sheetId, String user, String role) {
        if (!SheetRole.CLERK.equals(role) && !SheetRole.STEWARD.equals(role) && !SheetRole.VIEWER.equals(role)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A role is clerk, steward or viewer.");
        }
        sheets.lock(sheetId);
        SheetRole r = roles.findBySheetIdAndUser(sheetId, user).orElseGet(() -> new SheetRole(sheetId, user, role));
        r.setRole(role);
        SheetRole saved;
        try {
            saved = roles.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That role was just set by someone else.");
        }
        audit.recordOnCommit("ROLE_SET sheet=" + sheetId + " by=" + by + " user=" + user + " role=" + role);
        return saved;
    }

    /**
     * Sets the roles that may take the action, a comma separated list of clerk and steward; blank (null) for the
     * standing rule. Refused for an action not in the matrix or a role that is neither. Not dated.
     */
    @Transactional
    public Sheet setPermission(String by, String sheetId, String action, String roles) {
        if (!STANDING_PERMISSIONS.containsKey(action)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such action.");
        }
        String value = null;
        if (roles != null && !roles.isBlank()) {
            List<String> listed = Arrays.stream(roles.split(",", -1)).map(String::strip).toList();
            if (listed.stream().anyMatch(r -> !SheetRole.CLERK.equals(r) && !SheetRole.STEWARD.equals(r))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "List the roles as clerk, steward or both, separated by commas.");
            }
            value = String.join(",", listed.stream().distinct().toList());
        }
        Sheet sheet = sheets.lock(sheetId);
        sheet.setPermission(action, value);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=perm-" + action + " value="
                + (value == null ? "" : value));
        return saved;
    }

    /** Whether the user's role may take the action on the sheet: as the matrix sets it, else the standing rule. */
    @Transactional(readOnly = true)
    public boolean may(String sheetId, String user, String action) {
        String set = sheets.load(sheetId).getPermissions().get(action);
        List<String> allowed = set == null ? STANDING_PERMISSIONS.get(action) : List.of(set.split(","));
        return allowed.contains(roleOf(sheetId, user));
    }

    /** Refuses the user the action when their role may not take it on the sheet. */
    public void refuseUnlessMay(String sheetId, String user, String action) {
        if (!may(sheetId, user, action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not " + action + " here.");
        }
    }

    /** Records the sheet's clerk limit (null for no limit) from the date; null for the sheet's today. */
    @Transactional
    public Sheet setClerkLimit(String by, String sheetId, BigDecimal limit, LocalDate from) {
        return record(by, sheetId, Sheet.CLERK_LIMIT, limit == null ? null : money(limit), from, false);
    }

    /**
     * Records the sheet's terms from the date (null for the sheet's today): the days after its date a claim dated on
     * or after it is due by. Claims already added keep the due-by they were given.
     */
    @Transactional
    public Sheet setTerms(String by, String sheetId, int terms, LocalDate from) {
        return record(by, sheetId, Sheet.TERMS, String.valueOf(terms), from, false);
    }

    /**
     * Records the terms for one grade from the date (null for the sheet's today): the days after its date a claim is
     * due by when it is dated on or after it and its person has the grade on its date. Claims already added keep the
     * due-by they were given.
     */
    @Transactional
    public Sheet setGradeTerms(String by, String sheetId, String grade, int terms, LocalDate from) {
        return record(by, sheetId, Sheet.TERMS + "-" + grade, String.valueOf(terms), from, false);
    }

    /**
     * Records the sheet's late percent from the date (null for the sheet's today): the percent of an overdue claim's
     * figure added for each 30-day boundary past its due-by falling on or after it. Settled figures keep what
     * they were; the live figures it moves are rolled up.
     */
    @Transactional
    public Sheet setLatePct(String by, String sheetId, BigDecimal latePct, LocalDate from) {
        return record(by, sheetId, Sheet.LATE_PCT, money(latePct), from, true);
    }

    /**
     * Sets the sheet's policy date (null for blank: the sheet's today): a claim rounded by a date before it rounds
     * half up, one on or after it half to even. Settled and stated figures keep what they were; the live figures it
     * moves are rolled up.
     */
    @Transactional
    public Sheet setPolicyDate(String by, String sheetId, LocalDate policyDate) {
        Sheet sheet = sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setPolicyDate(policyDate);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=policy-date value="
                + (policyDate == null ? "" : policyDate.toString()));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Sets the sheet's period numbering from (null for blank: off): while it is set and the sheet's today is on or
     * after it, a claim opened is numbered within its period. Claims already numbered keep their numbers.
     */
    @Transactional
    public Sheet setPeriodNumberingFrom(String by, String sheetId, LocalDate from) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setPeriodNumberingFrom(from);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=period-numbering-from value="
                + (from == null ? "" : from.toString()));
        return saved;
    }

    /**
     * Sets the sheet's grace days (null for blank: off): once the sheet's today is changed, every open period whose
     * to-date plus them is before the new today is closed. Not dated.
     */
    @Transactional
    public Sheet setGraceDays(String by, String sheetId, Integer days) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setGraceDays(days);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=grace-days value="
                + (days == null ? "" : days.toString()));
        return saved;
    }

    /**
     * Records the sheet's floor from the date (null for the sheet's today): the least the live due of a claim dated
     * on or after it can be. Settled, stated and closed-period figures keep what they were; the live figures it moves
     * are rolled up.
     */
    @Transactional
    public Sheet setFloor(String by, String sheetId, BigDecimal floor, LocalDate from) {
        return record(by, sheetId, Sheet.FLOOR, money(floor), from, true);
    }

    /**
     * Records the sheet's cap (null for none) from the date (null for the sheet's today): the most a person's
     * due-total can be while it is in force on the sheet's today. The due-totals it moves are rolled up.
     */
    @Transactional
    public Sheet setCap(String by, String sheetId, BigDecimal cap, LocalDate from) {
        return record(by, sheetId, Sheet.CAP, cap == null ? null : money(cap), from, true);
    }

    /**
     * Records the sheet's proof threshold (null for none) from the date (null for the sheet's today): a claim dated
     * on or after it whose due is above it cannot be settled or approved while it has no proof.
     */
    @Transactional
    public Sheet setProofThreshold(String by, String sheetId, BigDecimal threshold, LocalDate from) {
        return record(by, sheetId, Sheet.PROOF_THRESHOLD, threshold == null ? null : money(threshold), from, false);
    }

    /**
     * Records the dated setting's value (null for none) from the date, or from the sheet's today when none is given,
     * replacing a value already recorded from that date. The audit line carries the from-date only when one was
     * given. When it can move live figures, the ones it moves are rolled up.
     */
    private Sheet record(String by, String sheetId, String key, String value, LocalDate from, boolean rollsUp) {
        Sheet sheet = sheets.lock(sheetId);
        RollupService.Snapshot before = rollsUp ? rollup.snapshot(sheetId) : null;
        sheet.record(key, from == null ? sheet.getToday() : from, value);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=" + key + " value="
                + (value == null ? "" : value) + (from == null ? "" : " from=" + from));
        if (rollsUp) {
            rollup.recordChanges(by, sheetId, before);
        }
        return saved;
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * Whether the user settling a claim with the due (null for n/a) needs a steward's approval: they are a clerk and
     * the due is above the sheet's clerk limit. A steward settles anything, and an n/a due is within any limit.
     */
    @Transactional(readOnly = true)
    public boolean aboveClerkLimit(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = sheets.load(sheetId).getClerkLimit();
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return false;
        }
        return !isSteward(sheetId, user);
    }

    /** Whether the user is a viewer of the sheet, who changes nothing. */
    @Transactional(readOnly = true)
    public boolean isViewer(String sheetId, String user) {
        return SheetRole.VIEWER.equals(roleOf(sheetId, user));
    }

    /** Whether the user is a steward of the sheet. */
    @Transactional(readOnly = true)
    public boolean isSteward(String sheetId, String user) {
        return SheetRole.STEWARD.equals(roleOf(sheetId, user));
    }

    /** Whether the sheet has been archived, which refuses every change on it. */
    @Transactional(readOnly = true)
    public boolean isArchived(String sheetId) {
        return sheetRepository.findById(sheetId).map(Sheet::isArchived).orElse(false);
    }

    /** Whether the user may change nothing on the sheet: they are a viewer, or the sheet is archived. */
    @Transactional(readOnly = true)
    public boolean isReadOnly(String sheetId, String user) {
        return isArchived(sheetId) || isViewer(sheetId, user);
    }
}
