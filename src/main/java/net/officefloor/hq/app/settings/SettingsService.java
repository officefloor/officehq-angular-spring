package net.officefloor.hq.app.settings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
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

    /** Sets the user's role on the sheet; refused unless it is clerk, steward, viewer or restricted. */
    @Transactional
    public SheetRole setRole(String by, String sheetId, String user, String role) {
        if (!SheetRole.CLERK.equals(role) && !SheetRole.STEWARD.equals(role) && !SheetRole.VIEWER.equals(role)
                && !SheetRole.RESTRICTED.equals(role)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A role is clerk, steward, viewer or restricted.");
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
     * Records the clerk limit for one grade (null for none) from the date (null for the sheet's today): a claim whose
     * person has the grade takes it in place of the sheet's while one is set.
     */
    @Transactional
    public Sheet setGradeClerkLimit(String by, String sheetId, String grade, BigDecimal limit, LocalDate from) {
        return record(by, sheetId, Sheet.CLERK_LIMIT + "-" + grade, limit == null ? null : money(limit), from, false);
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
     * Records the grace days for one grade (null for none) from the date (null for the sheet's today): a claim dated on
     * or after it whose person has the grade on its date is overdue only once its due-by plus them is before the
     * sheet's today, and its late charge's 30-day boundaries count from there. The live figures it moves are rolled up.
     */
    @Transactional
    public Sheet setGradeGrace(String by, String sheetId, String grade, Integer days, LocalDate from) {
        return record(by, sheetId, Sheet.GRACE + "-" + grade, days == null ? null : String.valueOf(days), from, true);
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
     * Sets the sheet's entry date (null for blank: off): while it is set, a new claim with no date is dated on it
     * rather than the sheet's today. Not dated.
     */
    @Transactional
    public Sheet setEntryDate(String by, String sheetId, LocalDate entryDate) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setEntryDate(entryDate);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=entry-date value="
                + (entryDate == null ? "" : entryDate.toString()));
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
     * Sets the sheet's review SLA (null for blank: none): a REVIEW claim whose review day (the sheet's today when it
     * went to REVIEW) plus these many days is before the sheet's today is late. Not dated.
     */
    @Transactional
    public Sheet setReviewSla(String by, String sheetId, Integer days) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setReviewSla(days);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=review-sla value="
                + (days == null ? "" : days.toString()));
        return saved;
    }

    /**
     * Sets the sheet's maximum of open claims per person (null for blank: none): opening a claim is refused while the
     * person already has this many OPEN or PENDING claims. Not dated.
     */
    @Transactional
    public Sheet setMaxOpen(String by, String sheetId, Integer max) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setMaxOpen(max);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=max-open value="
                + (max == null ? "" : max.toString()));
        return saved;
    }

    /**
     * Sets the sheet's proof kinds (null for blank: none), each receipt or photo, comma separated: while set, a claim
     * whose due is above its proof threshold needs at least one proof of each before it can be settled or approved.
     * Refused for any other kind. Not dated.
     */
    @Transactional
    public Sheet setProofKinds(String by, String sheetId, String kinds) {
        String list = null;
        if (kinds != null && !kinds.isBlank()) {
            List<String> each = new ArrayList<>();
            for (String k : kinds.split(",")) {
                String kind = k.trim();
                if (!Claim.PROOF_KINDS.contains(kind)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Each proof kind must be receipt or photo.");
                }
                if (!each.contains(kind)) {
                    each.add(kind);
                }
            }
            list = String.join(",", each);
        }
        Sheet sheet = sheets.lock(sheetId);
        sheet.setProofKinds(list);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=proof-kinds value="
                + (list == null ? "" : list));
        return saved;
    }

    /**
     * Sets the sheet's default grade (null for blank: A), one of the grades: the grade the quick-add bar gives a new
     * person and an import line with a blank grade uses. Refused for anything else. Changing it changes no one. Not
     * dated.
     */
    @Transactional
    public Sheet setDefaultGrade(String by, String sheetId, String grade) {
        String value = grade == null || grade.isBlank() ? null : grade.strip();
        Sheet sheet = sheets.lock(sheetId);
        if (value != null && !sheet.isGrade(value)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The default grade must be a grade.");
        }
        sheet.setDefaultGrade(value);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=default-grade value="
                + (value == null ? "" : value));
        return saved;
    }

    /**
     * Adds a grade to the sheet: a code of one to three capital letters (surrounding spaces dropped) not yet one of its
     * grades. Refused for anything else. Not dated.
     */
    @Transactional
    public Sheet addGrade(String by, String sheetId, String code) {
        String value = code == null ? "" : code.strip();
        if (!value.matches(Sheet.GRADE_CODE)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A grade is one to three capital letters.");
        }
        Sheet sheet = sheets.lock(sheetId);
        if (sheet.isGrade(value)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Grade " + value + " is already a grade.");
        }
        sheet.addGrade(value);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("GRADE_ADDED sheet=" + sheetId + " by=" + by + " grade=" + value);
        return saved;
    }

    /**
     * Retires the grade, one of the grades not yet retired: it can no longer be given to a new person or chosen in an
     * edit, while the people on it keep it. Refused for anything else. Not dated.
     */
    @Transactional
    public Sheet retireGrade(String by, String sheetId, String grade) {
        String value = grade == null ? "" : grade.strip();
        Sheet sheet = sheets.lock(sheetId);
        if (!sheet.isGrade(value)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The grade must be one of the grades.");
        }
        if (sheet.isGradeRetired(value)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Grade " + value + " is already retired.");
        }
        sheet.retireGrade(value);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("GRADE_RETIRED sheet=" + sheetId + " by=" + by + " grade=" + value);
        return saved;
    }

    /**
     * Sets the sheet's escalation gap (null for blank: off): reminding a claim whose previous reminder was at least
     * these many days before the sheet's today escalates it. Not dated.
     */
    @Transactional
    public Sheet setEscalateAfter(String by, String sheetId, Integer days) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setEscalateAfter(days);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=escalate-after value="
                + (days == null ? "" : days.toString()));
        return saved;
    }

    /**
     * Sets the sheet's mid score threshold (null for blank: none): while the high one is set too, a person whose score
     * is at or above it is MID. Not dated.
     */
    @Transactional
    public Sheet setScoreMid(String by, String sheetId, Integer score) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setScoreMid(score);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=score-mid value="
                + (score == null ? "" : score.toString()));
        return saved;
    }

    /**
     * Turns the sheet's score log on or off: while on, changing the sheet's today writes a SCORE line per person with
     * their score on the day being left. Not dated.
     */
    @Transactional
    public Sheet setScoreLog(String by, String sheetId, boolean on) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setScoreLog(on);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=score-log value="
                + (on ? "on" : ""));
        return saved;
    }

    /**
     * Sets the sheet's high score threshold (null for blank: none): while the mid one is set too, a person whose score
     * is at or above it is HIGH. Not dated.
     */
    @Transactional
    public Sheet setScoreHigh(String by, String sheetId, Integer score) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setScoreHigh(score);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=score-high value="
                + (score == null ? "" : score.toString()));
        return saved;
    }

    /**
     * Sets the sheet's pot (null for blank: none): shared among the members of each balanced pool by their shares.
     * Not dated. The due-totals its cuts move under the cap are rolled up.
     */
    @Transactional
    public Sheet setPot(String by, String sheetId, BigDecimal pot) {
        BigDecimal scaled = pot == null ? null : pot.setScale(2, RoundingMode.UNNECESSARY);
        Sheet sheet = sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setPot(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=pot value="
                + (scaled == null ? "" : scaled.toPlainString()));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Sets the sheet's alert threshold (null for blank: off): a person whose due-total crosses above it is alerted.
     * Not dated.
     */
    @Transactional
    public Sheet setAlertThreshold(String by, String sheetId, BigDecimal threshold) {
        BigDecimal scaled = threshold == null ? null : threshold.setScale(2, RoundingMode.UNNECESSARY);
        Sheet sheet = sheets.lock(sheetId);
        sheet.setAlertThreshold(scaled);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=alert-threshold value="
                + (scaled == null ? "" : scaled.toPlainString()));
        return saved;
    }

    /** Sets the month (1 to 12) the sheet's fiscal years start in. Not dated. */
    @Transactional
    public Sheet setFiscalStartMonth(String by, String sheetId, int month) {
        Sheet sheet = sheets.lock(sheetId);
        sheet.setFiscalStartMonth(month);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=fiscal-start-month value="
                + month);
        return saved;
    }

    /**
     * Sets the sheet's parent sheet (null for blank: none), whose rates apply to a grade with no rate in force on a
     * date on this sheet. Refused for the sheet itself, for a sheet never loaded, and for a sheet that has this sheet
     * as an ancestor. Not dated. The live figures it moves are rolled up.
     */
    @Transactional
    public Sheet setParent(String by, String sheetId, String parentId) {
        Sheet sheet = sheets.lock(sheetId);
        if (parentId != null) {
            if (parentId.equals(sheetId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "A sheet cannot be its own parent.");
            }
            if (!sheetRepository.existsById(parentId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "The parent must be a sheet that has been loaded.");
            }
            Set<String> seen = new HashSet<>();
            for (String at = parentId; at != null && seen.add(at);
                    at = sheetRepository.findById(at).map(Sheet::getParentId).orElse(null)) {
                if (at.equals(sheetId)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "That sheet has this sheet as an ancestor.");
                }
            }
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        sheet.setParentId(parentId);
        Sheet saved = sheetRepository.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + sheetId + " by=" + by + " key=parent value="
                + (parentId == null ? "" : parentId));
        rollup.recordChanges(by, sheetId, before);
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
     * Records the sheet's rounding mode, half-up or half-even (null for none), from the date (null for the sheet's
     * today): a claim whose rounding follows a date it is in force on rounds every live figure by it; one with none in
     * force rounds by the policy date. Refused for any other value. Settled and stated figures keep what they were;
     * the live figures it moves are rolled up.
     */
    @Transactional
    public Sheet setRounding(String by, String sheetId, String mode, LocalDate from) {
        String value = mode == null || mode.isBlank() ? null : mode.strip();
        if (value != null && !Sheet.HALF_UP.equals(value) && !Sheet.HALF_EVEN.equals(value)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A rounding mode is half-up or half-even, or blank for none.");
        }
        return record(by, sheetId, Sheet.ROUNDING, value, from, true);
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
     * Records the proof threshold for one grade (null for none) from the date (null for the sheet's today): a claim
     * dated on or after it whose person has the grade on its date takes it in place of the sheet's while one is set.
     */
    @Transactional
    public Sheet setGradeProofThreshold(String by, String sheetId, String grade, BigDecimal threshold, LocalDate from) {
        return record(by, sheetId, Sheet.PROOF_THRESHOLD + "-" + grade, threshold == null ? null : money(threshold),
                from, false);
    }

    /**
     * Records the sheet's note threshold (null for none) from the date (null for the sheet's today): a claim dated on
     * or after it whose due is above it cannot be opened while its note is empty.
     */
    @Transactional
    public Sheet setNoteThreshold(String by, String sheetId, BigDecimal threshold, LocalDate from) {
        return record(by, sheetId, Sheet.NOTE_THRESHOLD, threshold == null ? null : money(threshold), from, false);
    }

    /**
     * Records the sheet's review threshold (null for none) from the date (null for the sheet's today): a claim dated
     * on or after it whose due is above it goes to REVIEW when opened, for a steward to review.
     */
    @Transactional
    public Sheet setReviewThreshold(String by, String sheetId, BigDecimal threshold, LocalDate from) {
        return record(by, sheetId, Sheet.REVIEW_THRESHOLD, threshold == null ? null : money(threshold), from, false);
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
     * the due is above the clerk limit in force for the grade (the person's grade's when one is set, else the
     * sheet's). A steward settles anything, and an n/a due is within any limit.
     */
    @Transactional(readOnly = true)
    public boolean aboveClerkLimit(String sheetId, String user, String grade, BigDecimal due) {
        BigDecimal limit = sheets.load(sheetId).clerkLimitFor(grade);
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
