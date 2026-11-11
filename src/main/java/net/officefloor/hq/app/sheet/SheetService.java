package net.officefloor.hq.app.sheet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.PoolService;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Loads a sheet by id; a sheet never loaded before is started empty, its today that of the sheet the
 * user had open when they loaded it (the real date when none).
 */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;
    private final PeriodRepository periods;
    private final Audit audit;
    private final ClaimRepository claims;
    private final FiscalClaimNumberRepository fiscalNumbers;
    private final JdbcTemplate jdbc;
    /** The pools, whose members' cuts a closing period fixes (looked up when needed: they rely on this service). */
    private final ObjectProvider<PoolService> pools;

    public SheetService(SheetRepository sheets, RollupService rollup, PeriodRepository periods, Audit audit,
            ClaimRepository claims, FiscalClaimNumberRepository fiscalNumbers, JdbcTemplate jdbc,
            ObjectProvider<PoolService> pools) {
        this.jdbc = jdbc;
        this.pools = pools;
        this.fiscalNumbers = fiscalNumbers;
        this.audit = audit;
        this.claims = claims;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
    }

    @Transactional
    public Sheet load(String id, String user) {
        return load(id, user, null);
    }

    /** Load a sheet; one never loaded before starts with the today of the sheet {@code openId} (when there is one). */
    @Transactional
    public Sheet load(String id, String user, String openId) {
        return sheets.findById(id).orElseGet(() -> {
            LocalDate today = (openId == null ? Optional.<Sheet>empty() : sheets.findById(openId))
                    .map(Sheet::getToday).orElseGet(LocalDate::now);
            try {
                return sheets.saveAndFlush(new Sheet(id, user, Instant.now(), today));
            } catch (DataIntegrityViolationException e) {
                // another user started the same sheet at the same moment
                return sheets.findById(id).orElseThrow(() -> e);
            }
        });
    }

    /**
     * The other sheets of the sheet's tree, among the sheets that exist: its ancestors, and its
     * descendants and their descendants.
     */
    @Transactional(readOnly = true)
    public Set<String> tree(String id) {
        Set<String> tree = new LinkedHashSet<>();
        String at = sheets.findById(id).map(Sheet::getParentId).orElse(null);
        while (at != null && !at.equals(id) && sheets.existsById(at) && tree.add(at)) {
            at = sheets.findById(at).map(Sheet::getParentId).orElse(null);
        }
        Deque<String> below = new ArrayDeque<>(List.of(id));
        while (!below.isEmpty()) {
            for (Sheet child : sheets.findByParentIdOrderByIdAsc(below.pop())) {
                if (!child.getId().equals(id) && tree.add(child.getId())) {
                    below.push(child.getId());
                }
            }
        }
        return tree;
    }

    /** The sheet's today, for any date-dependent decision or default date on it. */
    @Transactional(readOnly = true)
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(LocalDate::now);
    }

    /** The grade a new person on the sheet takes when none is given: its default grade, else A. */
    @Transactional(readOnly = true)
    public String defaultGrade(String id) {
        return sheets.findById(id).map(Sheet::defaultGradeOrA).orElse("A");
    }

    /** The default date of a new claim on the sheet: its entry date when set, else its today. */
    @Transactional(readOnly = true)
    public LocalDate entryDate(String id) {
        return sheets.findById(id).map(Sheet::entryDateOrToday).orElseGet(LocalDate::now);
    }

    /** The sheet as the store holds it now; a sheet never loaded reads as a new one, today the real date. */
    @Transactional(readOnly = true)
    public Sheet sheet(String id) {
        return sheets.findById(id).orElseGet(() -> new Sheet(id, null, null, LocalDate.now()));
    }

    /**
     * The clerk limit in force on the sheet's today for a person of the grade: the grade's own when one
     * is set, else the sheet's; the most a clerk may settle a claim's due at; null for no limit.
     */
    @Transactional(readOnly = true)
    public BigDecimal clerkLimit(String id, String grade) {
        return sheets.findById(id).map(s -> s.clerkLimitFor(grade)).orElse(null);
    }

    /** A person's due-total held to the sheet's cap in force on its today (none: as it is; n/a stays n/a). */
    @Transactional(readOnly = true)
    public BigDecimal capped(String id, BigDecimal dueTotal) {
        return sheets.findById(id).map(s -> s.capped(dueTotal)).orElse(dueTotal);
    }

    /** Refuse (423) any change to an archived sheet: it stays visible and may not be changed. */
    // a refusal leaves the caller's transaction usable (a bulk action refuses one claim and goes on)
    @Transactional(readOnly = true, noRollbackFor = ResponseStatusException.class)
    public void refuseIfArchived(String id) {
        if (sheet(id).isArchived()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "This sheet is archived and may not be changed.");
        }
    }

    /** Archive the sheet; refused (409) when it is already archived. The caller checks the user is a steward. */
    @Transactional
    public Sheet archive(String id, String user) {
        load(id, user);
        Sheet sheet = lock(id);
        if (sheet.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This sheet is already archived.");
        }
        sheet.archive();
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + user);
        return saved;
    }

    /** Lock the sheet until the caller's transaction ends; refused (404) when there is no such sheet. */
    @Transactional
    public Sheet lock(String id) {
        return sheets.findLockedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet."));
    }

    /**
     * Take the next settle batch number on the sheet (1, 2, ...). The sheet stays locked until the
     * caller's transaction ends, so two batches at the same moment take different numbers.
     */
    @Transactional
    public int nextBatch(String id) {
        Sheet sheet = lock(id);
        int batch = sheet.nextBatch();
        sheets.saveAndFlush(sheet);
        return batch;
    }

    /**
     * The next number for a claim due by {@code date} settled on the sheet, never reused. Tree-wide
     * per fiscal year ({@code FY<yyyy>-<n>}, n counting from 1 within the fiscal year {@code date}
     * falls in by the sheet's fiscal start month): the sheets of a tree (a root and every sheet below
     * it) share one sequence per fiscal year, each number the next after the last given in that
     * fiscal year anywhere in the tree; unless the sheet numbers by period
     * on its today: then within the period the date falls in ({@code <period name>-<n>}), or in no period its X sequence
     * ({@code X-<n>}), each counting from 1. The sheet stays locked until the caller's transaction
     * ends, so two claims opened at the same moment take different numbers.
     */
    @Transactional
    public String nextClaimNumber(String id, LocalDate date) {
        Sheet sheet = lock(id);
        if (!sheet.numbersByPeriod()) {
            // the sheets of a tree share one sequence; holding the tree's root keeps two sheets of it
            // from giving out the same number at once
            String root = treeRoot(id);
            if (!root.equals(id)) {
                lock(root);
            }
            // the whole tree: the root and every sheet below it, side branches included
            Set<String> whole = new HashSet<>(tree(root));
            whole.add(root);
            String fiscalYear = Sheet.fiscalYearOf(date, sheet.getFiscalStartMonth());
            FiscalClaimNumber counter = fiscalNumbers.findById(new FiscalClaimNumber.Key(id, fiscalYear))
                    .orElseGet(() -> new FiscalClaimNumber(id, fiscalYear));
            int number = counter.next(fiscalNumbers.maxLastNumber(whole, fiscalYear));
            fiscalNumbers.saveAndFlush(counter);
            return fiscalYear + "-" + number;
        }
        Optional<Period> period = periods.findBySheetIdAndFromDateLessThanEqualAndToDateGreaterThanEqual(id, date,
                date);
        if (period.isPresent()) {
            int number = period.get().nextClaimNumber();
            periods.saveAndFlush(period.get());
            return period.get().getName() + "-" + number;
        }
        int number = sheet.nextUnperiodClaimNumber();
        sheets.saveAndFlush(sheet);
        return "X-" + number;
    }

    /** The root of the sheet's tree: its topmost ancestor, or the sheet itself when it has no parent. */
    private String treeRoot(String id) {
        Set<String> seen = new HashSet<>();
        String at = id;
        while (seen.add(at)) {
            String parent = sheets.findById(at).map(Sheet::getParentId).orElse(null);
            if (parent == null) {
                break;
            }
            at = parent;
        }
        return at;
    }

    /**
     * Close an open period, fixing its due-total and each pool member's cut now, and record it ({@code auto=yes} when closed by
     * a change of the sheet's today). The caller holds the sheet.
     */
    @Transactional
    public Period closePeriod(String id, String user, Period period, boolean auto) {
        period.close(rollup.dueBetweenByPerson(id, period.getFromDate(), period.getToDate()),
                rollup.carriedByPerson(id, period), pools.getObject().cutsNow(id));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + id + " by=" + user + " period=" + saved.getName() + " total="
                + saved.getClosedDue().setScale(2, RoundingMode.UNNECESSARY).toPlainString() + (auto ? " auto=yes" : ""));
        return saved;
    }

    /** Soft-close an open period, fixing its due-total and each pool member's cut now, and record it. The caller holds the sheet. */
    @Transactional
    public Period softClosePeriod(String id, String user, Period period) {
        period.softClose(rollup.dueBetweenByPerson(id, period.getFromDate(), period.getToDate()),
                rollup.carriedByPerson(id, period), pools.getObject().cutsNow(id));
        Period saved = periods.saveAndFlush(period);
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + id + " by=" + user + " period=" + saved.getName()
                + " total=" + saved.getClosedDue().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        return saved;
    }

    /**
     * Change the sheet's today. With grace days set, every open (not soft-closed) period whose to-date
     * plus the grace days is before the new today is then closed, in order of their to-dates. A new today releases
     * every claim editor's lock. A new today first records each person's due-total on the day being left (SNAPSHOT),
     * in list order. While the score log is on, that is preceded by each person's score on the day being left
     * (SCORE), in list order.
     */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        refuseIfArchived(id);
        load(id, user);
        // hold the sheet so no claim changes while periods close
        Sheet sheet = lock(id);
        RollupService.Snapshot before = rollup.snapshot(id);
        boolean moved = !today.equals(sheet.getToday());
        if (moved && sheet.isScoreLog()) {
            // each person's score as it stood on the day being left, ahead of the snapshot lines
            for (RollupService.Score score : rollup.scores(id)) {
                audit.recordOnCommit("SCORE sheet=" + id + " by=" + user + " name=" + score.name()
                        + " score=" + score.score());
            }
        }
        if (moved) {
            // each person's due-total as it stood on the day being left, before anything else the change records,
            // kept as their snapshot line
            for (Map.Entry<Long, RollupService.Figures> entry : before.people().entrySet()) {
                RollupService.Figures figures = entry.getValue();
                jdbc.update("INSERT INTO person_snapshot (person_id, pos, left_on, due_total) SELECT ?,"
                        + " COALESCE(MAX(pos), 0) + 1, ?, ? FROM person_snapshot WHERE person_id = ?",
                        entry.getKey(), sheet.getToday(), figures.dueTotal(), entry.getKey());
                audit.recordOnCommit("SNAPSHOT sheet=" + id + " by=" + user + " name=" + figures.name()
                        + " due-total=" + (figures.dueTotal() == null ? "n/a"
                                : figures.dueTotal().setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
            }
        }
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        Integer grace = saved.getGraceDays();
        if (grace != null) {
            for (Period period : periods.findBySheetIdAndClosedFalseOrderByToDateAsc(id)) {
                // a soft-closed period keeps the figures it fixed: only an open one closes by grace days
                if (!period.isSoftClosed() && period.getToDate().plusDays(grace).isBefore(today)) {
                    closePeriod(id, user, period, true);
                }
            }
        }
        // changing today has no audit line of its own, only the periods it closes and the figures it moves
        rollup.recordChanges(id, user, before);
        if (moved) {
            claims.unlockAll(id);
        }
        return saved;
    }
}
