package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.rollup.Rollup;
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

    // The period service loads sheets through this one, so it is taken lazily.
    public SheetService(SheetRepository sheets, Rollup rollup, Audit audit, @Lazy PeriodService periods,
            ClaimRepository claims) {
        this.sheets = sheets;
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
     *  the day being left, and releases every claim editor's lock on the sheet. */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        load(id, user);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        Rollup.Figures before = rollup.of(id);
        boolean changed = !today.equals(sheet.getToday());
        if (changed) {
            before.people().values().forEach(figures -> audit.recordOnCommit("SNAPSHOT sheet=" + id + " by=" + user
                    + " name=" + figures.name() + " due-total=" + figures.dueTotal()));
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

    /** Record the sheet's note threshold (null: none) from a date (null: the sheet's today), starting the sheet if
     *  it has never been loaded. */
    @Transactional
    public Sheet setNoteThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return record(id, user, Sheet.NOTE_THRESHOLD, from, threshold == null ? null : scaled(threshold), false);
    }

    /** Set the roles that may take an action (a comma separated list of clerk and steward; blank: its standing
     *  rule), starting the sheet if it has never been loaded. Not dated. Refused for a role that is neither. */
    @Transactional
    public Sheet setPermission(String id, String user, String action, String roles) {
        List<String> listed = roles == null ? List.of()
                : Arrays.stream(roles.split(",")).map(String::trim).filter(r -> !r.isEmpty()).distinct().toList();
        if (listed.stream().anyMatch(r -> !Role.CLERK.equals(r) && !Role.STEWARD.equals(r))) {
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
