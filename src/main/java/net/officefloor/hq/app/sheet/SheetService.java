package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import net.officefloor.hq.app.Audit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.Rollups;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; an id never loaded before starts a new, empty sheet. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Rollups rollups;
    private final Audit audit;
    private final ApplicationEventPublisher events;
    private final PersonRepository people;

    public SheetService(SheetRepository sheets, Rollups rollups, Audit audit, ApplicationEventPublisher events,
            PersonRepository people) {
        this.sheets = sheets;
        this.people = people;
        this.rollups = rollups;
        this.audit = audit;
        this.events = events;
    }

    /** Load a sheet; a new sheet's today starts as the real date. */
    @Transactional
    public synchronized Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> sheets.save(new Sheet(id, Instant.now(), LocalDate.now())));
    }

    /**
     * The sheet's today. Every date-dependent decision and every default date on a sheet uses
     * this, never the real clock.
     */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return load(id).getToday();
    }

    /**
     * Take the sheet's lock, holding it until the caller's transaction ends, so changes that check
     * the sheet's other rows before writing are made one at a time.
     */
    @Transactional
    public Sheet lock(String id) {
        load(id);
        return sheets.findLockedById(id).orElseThrow();
    }

    /**
     * The sheets of the sheet's tree: its root (its topmost ancestor, or itself when it has no parent)
     * first, then all the root's descendants.
     */
    @Transactional(readOnly = true)
    public List<String> tree(String id) {
        List<String> ancestors = sheets.ancestors(id);
        String root = ancestors.isEmpty() ? id : ancestors.get(ancestors.size() - 1);
        List<String> tree = new ArrayList<>(List.of(root));
        tree.addAll(sheets.descendants(root));
        if (!tree.contains(id)) {
            tree.add(id);
        }
        return tree;
    }

    /**
     * Change the sheet's today, auditing the figures that moves. When it moves, each person's due-total
     * as it stood on the day being left is audited first, in list order, and kept as one of their snapshot lines, and the sheet as it stood at
     * the end of that day is kept (see {@link TodayLeaving}). The periods it puts past their grace
     * days are closed (see {@link TodayChanged}) before those figures are audited.
     */
    @Transactional
    public synchronized Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id);
        Rollups.Rollup before = rollups.take(id);
        if (!today.equals(sheet.getToday())) {
            LocalDate leaving = sheet.getToday();
            before.people().forEach((personId, figures) -> {
                people.findById(personId).ifPresent(person -> person.snapshot(leaving, figures.dueTotal()));
                audit.recordOnCommit("SNAPSHOT sheet=" + id + " by=" + user
                        + " name=" + figures.name() + " due-total=" + Rollups.shown(figures.dueTotal()));
            });
            events.publishEvent(new TodayLeaving(id, sheet.getToday()));
        }
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        events.publishEvent(new TodayChanged(id, user, today, saved.getGraceDays()));
        rollups.audit(id, user, before);
        return saved;
    }

    /** Whether the sheet is archived: nothing on it may change. */
    @Transactional(readOnly = true)
    public boolean archived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }

    /** Archive the sheet; refused when it already is. */
    @Transactional
    public Sheet archive(String id, String user) {
        Sheet sheet = lock(id);
        if (sheet.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet is already archived.");
        }
        sheet.setArchived(true);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + user);
        return saved;
    }

    /** The sheet's clerk limit; null when there is none. */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String id) {
        return load(id).getClerkLimit();
    }

    /** Record the sheet's clerk limit (null: no limit) from a date (null: the sheet's today), auditing the change. */
    @Transactional
    public Sheet setClerkLimit(String id, String user, BigDecimal limit, LocalDate from) {
        return setDated(id, user, Sheet.CLERK_LIMIT, money(limit), from, false);
    }

    /** Record the sheet's cap (null: no cap) from a date, auditing the change and the figures it moves. */
    @Transactional
    public Sheet setCap(String id, String user, BigDecimal cap, LocalDate from) {
        return setDated(id, user, Sheet.CAP, money(cap), from, true);
    }

    /** Record the sheet's proof threshold (null: none) from a date, auditing the change. */
    @Transactional
    public Sheet setProofThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return setDated(id, user, Sheet.PROOF_THRESHOLD, money(threshold), from, false);
    }

    /** Record a grade's proof threshold (null: none, the sheet's applies) from a date, auditing the change. */
    @Transactional
    public Sheet setGradeProofThreshold(String id, String user, String grade, BigDecimal threshold, LocalDate from) {
        return setDated(id, user, Sheet.gradeProofThresholdKey(grade), money(threshold), from, false);
    }

    /** Record the sheet's note threshold (null: none) from a date, auditing the change. */
    @Transactional
    public Sheet setNoteThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return setDated(id, user, Sheet.NOTE_THRESHOLD, money(threshold), from, false);
    }

    /** Record the sheet's review threshold (null: none) from a date, auditing the change. */
    @Transactional
    public Sheet setReviewThreshold(String id, String user, BigDecimal threshold, LocalDate from) {
        return setDated(id, user, Sheet.REVIEW_THRESHOLD, money(threshold), from, false);
    }

    /** Record the sheet's floor from a date, auditing the change and the figures it moves. */
    @Transactional
    public Sheet setFloor(String id, String user, BigDecimal floor, LocalDate from) {
        return setDated(id, user, Sheet.FLOOR, money(floor), from, true);
    }

    /** Record the sheet's terms, in days, from a date, auditing the change; claims keep their due-by. */
    @Transactional
    public Sheet setTerms(String id, String user, int terms, LocalDate from) {
        return setDated(id, user, Sheet.TERMS, Integer.toString(terms), from, true);
    }

    /**
     * Record a grade's own terms, in days (null: the sheet's terms), from a date, auditing the change.
     * Claims keep their due-by.
     */
    @Transactional
    public Sheet setGradeTerms(String id, String user, String grade, Integer terms, LocalDate from) {
        return setDated(id, user, Sheet.gradeTermsKey(grade), terms == null ? null : terms.toString(), from, true);
    }

    /**
     * Record a grade's grace, in days (null: none), from a date, auditing the change and the figures it
     * moves. Claims keep their due-by.
     */
    @Transactional
    public Sheet setGradeGrace(String id, String user, String grade, Integer days, LocalDate from) {
        return setDated(id, user, Sheet.gradeGraceKey(grade), days == null ? null : days.toString(), from, true);
    }

    /** Record the sheet's late percent from a date, auditing the change and the figures it moves. */
    @Transactional
    public Sheet setLatePct(String id, String user, BigDecimal latePct, LocalDate from) {
        return setDated(id, user, Sheet.LATE_PCT, money(latePct), from, true);
    }

    /** Record the sheet's rounding mode (half-up or half-even; null: none) from a date, auditing the change and the figures it moves. */
    @Transactional
    public Sheet setRounding(String id, String user, String mode, LocalDate from) {
        return setDated(id, user, Sheet.ROUNDING, mode, from, true);
    }

    /**
     * Record a dated setting's value (null: none) from a date, the sheet's today when none is given,
     * replacing any value recorded from that date. The change is audited, with the from-date when one
     * was given, and so are the figures it moves when it can move any.
     */
    private Sheet setDated(String id, String user, String key, String value, LocalDate from, boolean movesFigures) {
        Sheet sheet = lock(id);
        Rollups.Rollup before = movesFigures ? rollups.take(id) : null;
        sheet.record(key, from == null ? sheet.getToday() : from, value);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=" + key + " value="
                + (value == null ? "" : value) + (from == null ? "" : " from=" + from));
        if (movesFigures) {
            rollups.audit(id, user, before);
        }
        return saved;
    }

    /** A figure with two decimals as text; null stays null. */
    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** Change the sheet's policy date (null: the sheet's today), auditing the change and the figures it moves. */
    @Transactional
    public Sheet setPolicyDate(String id, String user, LocalDate policyDate) {
        Sheet sheet = lock(id);
        Rollups.Rollup before = rollups.take(id);
        sheet.setPolicyDate(policyDate);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=policy-date value="
                + (saved.getPolicyDate() == null ? "" : saved.getPolicyDate().toString()));
        rollups.audit(id, user, before);
        return saved;
    }

    /** Change the sheet's period-numbering-from date (null: off), auditing the change. */
    @Transactional
    public Sheet setPeriodNumberingFrom(String id, String user, LocalDate from) {
        Sheet sheet = lock(id);
        sheet.setPeriodNumberingFrom(from);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=period-numbering-from value="
                + (saved.getPeriodNumberingFrom() == null ? "" : saved.getPeriodNumberingFrom().toString()));
        return saved;
    }

    /** Change the sheet's entry date (null: off), auditing the change. */
    @Transactional
    public Sheet setEntryDate(String id, String user, LocalDate entryDate) {
        Sheet sheet = lock(id);
        sheet.setEntryDate(entryDate);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=entry-date value="
                + (saved.getEntryDate() == null ? "" : saved.getEntryDate().toString()));
        return saved;
    }

    /** Change the sheet's grace days (null: off), auditing the change. */
    @Transactional
    public Sheet setGraceDays(String id, String user, Integer graceDays) {
        Sheet sheet = lock(id);
        sheet.setGraceDays(graceDays);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=grace-days value="
                + (saved.getGraceDays() == null ? "" : saved.getGraceDays().toString()));
        return saved;
    }

    /** Change the sheet's escalation gap in days (null: off), auditing the change. */
    @Transactional
    public Sheet setEscalateAfter(String id, String user, Integer days) {
        Sheet sheet = lock(id);
        sheet.setEscalateAfter(days);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=escalate-after value="
                + (saved.getEscalateAfter() == null ? "" : saved.getEscalateAfter().toString()));
        return saved;
    }

    /** Change the month, 1 to 12, the sheet's fiscal year starts in, auditing the change. */
    @Transactional
    public Sheet setFiscalStartMonth(String id, String user, int month) {
        if (month < 1 || month > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The fiscal start month is 1 to 12.");
        }
        Sheet sheet = lock(id);
        sheet.setFiscalStartMonth(month);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=fiscal-start-month value="
                + saved.getFiscalStartMonth());
        return saved;
    }

    /** Change the sheet's pot (null: none), auditing the change. */
    @Transactional
    public Sheet setPot(String id, String user, BigDecimal pot) {
        Sheet sheet = lock(id);
        sheet.setPot(pot == null ? null : pot.setScale(2, RoundingMode.UNNECESSARY));
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=pot value="
                + (saved.getPot() == null ? "" : saved.getPot().toPlainString()));
        return saved;
    }

    /** Change the sheet's alert threshold (null: off), auditing the change. */
    @Transactional
    public Sheet setAlertThreshold(String id, String user, BigDecimal threshold) {
        Sheet sheet = lock(id);
        sheet.setAlertThreshold(threshold == null ? null : threshold.setScale(2, RoundingMode.UNNECESSARY));
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=alert-threshold value="
                + (saved.getAlertThreshold() == null ? "" : saved.getAlertThreshold().toPlainString()));
        return saved;
    }

    /**
     * Change the sheet's parent (null: none), auditing the change and the figures it moves. The parent
     * must be a sheet that has been loaded, not this sheet, and not one that has this sheet as an ancestor.
     */
    @Transactional
    public synchronized Sheet setParent(String id, String user, String parentId) {
        Sheet sheet = lock(id);
        if (parentId != null) {
            if (parentId.equals(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A sheet cannot be its own parent.");
            }
            if (!sheets.existsById(parentId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No sheet " + parentId + " has been loaded.");
            }
            if (sheets.ancestors(parentId).contains(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Sheet " + parentId + " has this sheet as an ancestor.");
            }
        }
        Rollups.Rollup before = rollups.take(id);
        sheet.setParentId(parentId);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=parent value="
                + (parentId == null ? "" : parentId));
        rollups.audit(id, user, before);
        return saved;
    }

    /** Set the roles that may take an action (null: the standing rule), auditing the change. */
    @Transactional
    public Sheet setPermission(String id, String user, String action, String roles) {
        Sheet sheet = lock(id);
        sheet.setPermission(action, roles);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SETTING_CHANGED sheet=" + id + " by=" + user + " key=perm-" + action + " value="
                + (roles == null ? "" : roles));
        return saved;
    }
}
