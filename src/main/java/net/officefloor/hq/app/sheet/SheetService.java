package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.snapshot.PersonSnapshotService;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Loads sheets by id; a sheet never loaded before is started empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Rollup rollup;
    private final Audit audit;

    private final PeriodService periods;
    private final ClaimRepository claims;
    private final PersonSnapshotService snapshots;
    private final RoleService roles;

    // The period and role services load sheets through this one, so they are taken lazily.
    public SheetService(SheetRepository sheets, Rollup rollup, Audit audit, @Lazy PeriodService periods,
            ClaimRepository claims, PersonSnapshotService snapshots, @Lazy RoleService roles) {
        this.roles = roles;
        this.sheets = sheets;
        this.snapshots = snapshots;
        this.claims = claims;
        this.rollup = rollup;
        this.audit = audit;
        this.periods = periods;
    }

    /** The sheet with this id, re-read from the store; started empty if it has never been loaded. */
    @Transactional
    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(), LocalDate.now()));
            } catch (DataIntegrityViolationException raced) {
                // Another user started the same sheet at the same moment: theirs stands.
                return sheets.findById(id).orElseThrow(() -> raced);
            }
        });
    }

    /** The sheet's policy versions, oldest first, each with how many of its claims (not deleted) have it as their
     *  policy version (the one in force on the claim's date); starting the sheet if it has never been loaded. */
    @Transactional
    public List<SheetController.PolicyLineView> policyLines(String id, String user) {
        Sheet sheet = load(id, user);
        Map<Integer, Long> counts = claims.findBySheetIdOrderByListedDescIdDesc(id).stream()
                .filter(c -> c.getStatus() != ClaimStatus.DELETED)
                .collect(Collectors.groupingBy(c -> sheet.policyVersionOn(c.getDate()), Collectors.counting()));
        List<PolicyVersion> versions = sheet.getPolicyVersions();
        return IntStream.range(0, versions.size())
                .mapToObj(i -> new SheetController.PolicyLineView(i + 1, versions.get(i).getKey(),
                        versions.get(i).getFrom(), versions.get(i).getValue(), counts.getOrDefault(i + 1, 0L)))
                .toList();
    }

    /** The sheet's today, or null for a sheet never loaded. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElse(null);
    }

    /** Whether the sheet is archived; a sheet never loaded is not. */
    @Transactional(readOnly = true)
    public boolean isArchived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }

    /** Archive the sheet, starting it if it has never been loaded. Who may archive it is checked by the caller;
     *  an archived sheet is refused before it gets here (see {@link ArchiveGuard}), and again under the lock. */
    @Transactional
    public Sheet archive(String id, String user) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        if (sheet.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet is archived");
        }
        sheet.archive();
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SHEET_ARCHIVED sheet=" + id + " by=" + user);
        return saved;
    }

    /** Set the sheet's today, starting the sheet if it has never been loaded; then close every open period past its
     *  grace days. A change of today first records a SNAPSHOT line per person, in list order, with their due-total on
     *  the day being left (kept as the person's snapshot line), before them, while the score log is on, a SCORE line
     *  per person, in list order, with their score on the day being left, and releases every claim editor's lock on the sheet.
     *  Refused for a role the permission matrix does not allow to change today. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        if (!sheet.allows("today", roles.roleOf(id, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not change the sheet's today");
        }
        Rollup.Figures before = rollup.of(id);
        boolean changed = !today.equals(sheet.getToday());
        if (changed) {
            LocalDate left = sheet.getToday();
            if (sheet.isScoreLog()) {
                rollup.scoresOf(id).forEach((personId, score) -> audit.recordActingOnCommit(id, user,
                        "SCORE sheet=" + id + " by=" + user + " name=" + score.name() + " score=" + score.score()));
            }
            before.people().forEach((personId, figures) -> {
                snapshots.record(id, personId, left, figures.dueTotal(), today);
                audit.recordActingOnCommit(id, user, "SNAPSHOT sheet=" + id + " by=" + user
                        + " name=" + figures.name() + " due-total=" + figures.dueTotal());
            });
        }
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        periods.closePastGrace(saved, user);
        rollup.recordChanges(id, user, before);
        if (changed) {
            // Last, so no claim read before writes its lock back.
            claims.releaseLocks(id);
        }
        return saved;
    }

    /** Record the sheet's clerk limit (null: no limit) from a date (null: the sheet's today), starting the sheet if
     *  it has never been loaded. */
    @Transactional
    public Sheet setClerkLimit(String id, String user, BigDecimal limit, LocalDate from) {
        return record(id, user, Sheet.CLERK_LIMIT, from, limit == null ? null : scaled(limit), false);
    }

    /** Record the sheet's floor from a date (null: the sheet's today), starting the sheet if it has never been
     *  loaded. */
    @Transactional
    public Sheet setFloor(String id, String user, BigDecimal floor, LocalDate from) {
        return record(id, user, Sheet.FLOOR, from, scaled(floor), true);
    }

    /** Record the sheet's terms in days from a date (null: the sheet's today), starting the sheet if it has never
     *  been loaded. */
    @Transactional
    public Sheet setTerms(String id, String user, int terms, LocalDate from) {
        return record(id, user, Sheet.TERMS, from, String.valueOf(terms), false);
    }

    /** Record a grade's own terms in days (null: the sheet's terms) from a date (null: the sheet's today), starting
     *  the sheet if it has never been loaded. */
    @Transactional
    public Sheet setGradeTerms(String id, String user, String grade, Integer terms, LocalDate from) {
        return record(id, user, Sheet.TERMS + "-" + grade, from, terms == null ? null : String.valueOf(terms), false);
    }

    /** Record a grade's grace days (null: none) from a date (null: the sheet's today), starting the sheet if it has
     *  never been loaded. */
    @Transactional
    public Sheet setGradeGrace(String id, String user, String grade, Integer days, LocalDate from) {
        return record(id, user, Sheet.GRACE + "-" + grade, from, days == null ? null : String.valueOf(days), true);
    }

    /** Record the sheet's late percent from a date (null: the sheet's today), starting the sheet if it has never
     *  been loaded. */
    @Transactional
    public Sheet setLatePct(String id, String user, BigDecimal latePct, LocalDate from) {
        return record(id, user, Sheet.LATE_PCT, from, scaled(latePct), true);
    }

    /** Set the sheet's policy date (null: the sheet's today), starting the sheet if it has never been loaded. */
    @Transactional
    public Sheet setPolicyDate(String id, String user, LocalDate policyDate) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        Rollup.Figures before = rollup.of(id);
        sheet.setPolicyDate(policyDate);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=policy-date value=" + (policyDate == null ? "" : policyDate));
        rollup.recordChanges(id, user, before);
        return saved;
    }

    /** Set the sheet's entry date (null: off), starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setEntryDate(String id, String user, LocalDate entryDate) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setEntryDate(entryDate);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=entry-date value=" + (entryDate == null ? "" : entryDate));
        return saved;
    }

    /** Set the sheet's period numbering from (null: off), starting the sheet if it has never been loaded. */
    @Transactional
    public Sheet setPeriodNumberingFrom(String id, String user, LocalDate from) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setPeriodNumberingFrom(from);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=period-numbering-from value=" + (from == null ? "" : from));
        return saved;
    }

    /** Set the sheet's grace days (null: off), starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setGraceDays(String id, String user, Integer graceDays) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setGraceDays(graceDays);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=grace-days value=" + (graceDays == null ? "" : graceDays));
        return saved;
    }

    /** Set the sheet's escalation gap in days (null: off), starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setEscalateAfter(String id, String user, Integer escalateAfter) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setEscalateAfter(escalateAfter);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=escalate-after value=" + (escalateAfter == null ? "" : escalateAfter));
        return saved;
    }

    /** Set the sheet's review SLA in days (null: none), starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setReviewSla(String id, String user, Integer reviewSla) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setReviewSla(reviewSla);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=review-sla value=" + (reviewSla == null ? "" : reviewSla));
        return saved;
    }

    /** Set the sheet's pending SLA in days (null: none), starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setPendingSla(String id, String user, Integer pendingSla) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setPendingSla(pendingSla);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=pending-sla value=" + (pendingSla == null ? "" : pendingSla));
        return saved;
    }

    /** Set the sheet's maximum of open claims per person (null: none), starting the sheet if it has never been loaded.
     *  Not dated. */
    @Transactional
    public Sheet setMaxOpen(String id, String user, Integer maxOpen) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setMaxOpen(maxOpen);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=max-open value=" + (maxOpen == null ? "" : maxOpen));
        return saved;
    }

    /** Set the sheet's mid score threshold (null: none), starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setScoreMid(String id, String user, Integer scoreMid) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setScoreMid(scoreMid);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=score-mid value=" + (scoreMid == null ? "" : scoreMid));
        return saved;
    }

    /** Set the sheet's high score threshold (null: none), starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setScoreHigh(String id, String user, Integer scoreHigh) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setScoreHigh(scoreHigh);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=score-high value=" + (scoreHigh == null ? "" : scoreHigh));
        return saved;
    }

    /** Turn the sheet's score log switch on or off, starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setScoreLog(String id, String user, boolean on) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setScoreLog(on);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=score-log value=" + (on ? "on" : ""));
        return saved;
    }

    /** Set the sheet's fiscal year start month, 1 to 12, starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setFiscalStartMonth(String id, String user, int month) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setFiscalStartMonth(month);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=fiscal-start-month value=" + month);
        return saved;
    }

    /** Set the sheet's pot (null: none), starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setPot(String id, String user, BigDecimal pot) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        // The pot sets the cuts, which the cap counts against: record how the figures moved.
        Rollup.Figures before = rollup.of(id);
        BigDecimal scaled = pot == null ? null : pot.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setPot(scaled);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=pot value=" + (scaled == null ? "" : scaled.toPlainString()));
        rollup.recordChanges(id, user, before);
        return saved;
    }

    /** Set the sheet's alert threshold (null: off), starting the sheet if it has never been loaded. Not dated. */
    @Transactional
    public Sheet setAlertThreshold(String id, String user, BigDecimal threshold) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        BigDecimal scaled = threshold == null ? null : threshold.setScale(2, RoundingMode.UNNECESSARY);
        sheet.setAlertThreshold(scaled);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=alert-threshold value=" + (scaled == null ? "" : scaled.toPlainString()));
        return saved;
    }

    /** Set the sheet's proof kinds (comma separated, each receipt or photo; null: none), starting the sheet if it has
     *  never been loaded. Not dated. Refused for a kind listed more than once. */
    @Transactional
    public Sheet setProofKinds(String id, String user, String kinds) {
        if (kinds != null && Arrays.stream(kinds.split(",")).distinct().count() != kinds.split(",").length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A proof kind is listed more than once");
        }
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setProofKinds(kinds);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=proof-kinds value=" + (kinds == null ? "" : kinds));
        return saved;
    }

    /** Set the sheet's default grade (A, B or C; null: none, then A), starting the sheet if it has never been loaded.
     *  Not dated; no one's grade changes with it. */
    @Transactional
    public Sheet setDefaultGrade(String id, String user, String grade) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        requireGrade(sheet, grade);
        sheet.setDefaultGrade(grade);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=default-grade value=" + (grade == null ? "" : grade));
        return saved;
    }

    /** Retire a grade (A, B or C), starting the sheet if it has never been loaded: it can no longer be given to a new
     *  person nor chosen in an edit; people on it keep it. Refused for a grade already retired. Not dated. */
    @Transactional
    public Sheet retireGrade(String id, String user, String grade) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        requireGrade(sheet, grade);
        if (sheet.isGradeRetired(grade)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Grade " + grade + " is already retired");
        }
        sheet.retireGrade(grade);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "GRADE_RETIRED sheet=" + id + " by=" + user + " grade=" + grade);
        return saved;
    }

    /** Add a grade, starting the sheet if it has never been loaded: a code of one to three capital letters (trimmed)
     *  that is not yet a grade of the sheet. Not dated. */
    @Transactional
    public Sheet addGrade(String id, String user, String grade) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        if (sheet.hasGrade(grade)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Grade " + grade + " is already a grade");
        }
        sheet.addGrade(grade);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "GRADE_ADDED sheet=" + id + " by=" + user + " grade=" + grade);
        return saved;
    }

    /** Refuse a grade the sheet does not have. */
    public static void requireGrade(Sheet sheet, String grade) {
        if (grade != null && !sheet.hasGrade(grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No grade " + grade + " on this sheet");
        }
    }

    /** Set the sheet's parent sheet (null: none), starting the sheet if it has never been loaded. Not dated. Refused
     *  for the sheet itself, for a sheet never loaded, and for a sheet that has this one as an ancestor. */
    @Transactional
    public Sheet setParent(String id, String user, String parentId) {
        if (parentId != null) {
            if (parentId.equals(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A sheet cannot be its own parent");
            }
            if (!sheets.existsById(parentId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No sheet " + parentId + " has been loaded");
            }
            if (sheets.ancestorsOf(parentId, Integer.MAX_VALUE).contains(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Sheet " + parentId + " has this sheet as an ancestor");
            }
        }
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        Rollup.Figures before = rollup.of(id);
        sheet.setParentId(parentId);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user
                + " key=parent value=" + (parentId == null ? "" : parentId));
        rollup.recordChanges(id, user, before);
        return saved;
    }

    /** Record the sheet's cap (null: none) from a date (null: the sheet's today), starting the sheet if it has never
     *  been loaded. */
    @Transactional
    public Sheet setCap(String id, String user, BigDecimal cap, LocalDate from) {
        return record(id, user, Sheet.CAP, from, cap == null ? null : scaled(cap), true);
    }

    /** Record the sheet's proof threshold (null: none) from a date (null: the sheet's today), starting the sheet if
     *  it has never been loaded. */
    @Transactional
    public Sheet setProofThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return record(id, user, Sheet.PROOF_THRESHOLD, from, threshold == null ? null : scaled(threshold), false);
    }

    /** Record a grade's own proof threshold (null: the sheet's) from a date (null: the sheet's today), starting the
     *  sheet if it has never been loaded. */
    @Transactional
    public Sheet setGradeProofThreshold(String id, String user, String grade, BigDecimal threshold, LocalDate from) {
        return record(id, user, Sheet.PROOF_THRESHOLD + "-" + grade, from, threshold == null ? null : scaled(threshold), false);
    }

    /** Record a grade's own clerk limit (null: the sheet's) from a date (null: the sheet's today), starting the
     *  sheet if it has never been loaded. */
    @Transactional
    public Sheet setGradeClerkLimit(String id, String user, String grade, BigDecimal limit, LocalDate from) {
        return record(id, user, Sheet.CLERK_LIMIT + "-" + grade, from, limit == null ? null : scaled(limit), false);
    }

    /** Record the sheet's note threshold (null: none) from a date (null: the sheet's today), starting the sheet if
     *  it has never been loaded. */
    @Transactional
    public Sheet setNoteThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return record(id, user, Sheet.NOTE_THRESHOLD, from, threshold == null ? null : scaled(threshold), false);
    }

    /** Record the sheet's review threshold (null: none) from a date (null: the sheet's today), starting the sheet
     *  if it has never been loaded. */
    @Transactional
    public Sheet setReviewThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return record(id, user, Sheet.REVIEW_THRESHOLD, from, threshold == null ? null : scaled(threshold), false);
    }

    /** Record the sheet's rounding mode (half-up or half-even; null: none) from a date (null: the sheet's today),
     *  starting the sheet if it has never been loaded. Refused for any other mode. */
    @Transactional
    public Sheet setRounding(String id, String user, String mode, LocalDate from) {
        if (mode != null && !Sheet.ROUNDING_MODES.containsKey(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rounding mode is half-up or half-even");
        }
        return record(id, user, Sheet.ROUNDING, from, mode, true);
    }

    /** Set the roles that may take an action (a comma separated list of clerk and steward, and for today also
     *  restricted; blank: its standing rule), starting the sheet if it has never been loaded. Not dated. Refused for
     *  any other role. */
    @Transactional
    public Sheet setPermission(String id, String user, String action, String roles) {
        List<String> listed = roles == null ? List.of()
                : Arrays.stream(roles.split(",")).map(String::trim).filter(r -> !r.isEmpty()).distinct().toList();
        if (listed.stream().anyMatch(r -> !Role.CLERK.equals(r) && !Role.STEWARD.equals(r)
                && !("today".equals(action) && Role.RESTRICTED.equals(r)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A permission lists the roles clerk and steward");
        }
        String value = String.join(",", listed);
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        sheet.setPermission(action, value);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user + " key=perm-" + action + " value=" + value);
        return saved;
    }

    /** Record a dated setting's value (null: none) from a date (null: the sheet's today), replacing the one it had
     *  from that date; the audit line ends with the from-date when one was given. A setting a claim's due depends on
     *  records how the figures moved. */
    private Sheet record(String id, String user, String key, LocalDate from, String value, boolean movesFigures) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        Rollup.Figures before = movesFigures ? rollup.of(id) : null;
        sheet.record(key, from != null ? from : sheet.getToday(), value);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordChangeOnCommit(id, user, "SETTING_CHANGED sheet=" + id + " by=" + user + " key=" + key
                + " value=" + (value == null ? "" : value) + (from == null ? "" : " from=" + from));
        if (movesFigures) {
            rollup.recordChanges(id, user, before);
        }
        return saved;
    }

    private static String scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
