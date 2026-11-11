package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.PersonSnapshot;
import net.officefloor.hq.app.person.PersonSnapshotRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a sheet by id, starting an empty one the first time the id is used, and keeps the sheet's
 * today. The real clock is read only once, to give a new sheet its starting today; everything else
 * dated uses {@link #today(String)}.
 */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;
    private final PeriodService periods;
    private final RoleService roles;
    private final Audit audit;
    private final ClaimRepository claims;
    private final PersonSnapshotRepository snapshots;

    public SheetService(SheetRepository sheets, RollupService rollup, PeriodService periods, RoleService roles,
            Audit audit, ClaimRepository claims, PersonSnapshotRepository snapshots) {
        this.snapshots = snapshots;
        this.claims = claims;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
        this.roles = roles;
        this.audit = audit;
    }

    public Sheet load(String id, String user) {
        return sheets.findById(id).orElseGet(() -> start(id, user));
    }

    /** Whether the user is a viewer of the sheet, who may change nothing. */
    public boolean isViewer(String id, String user) {
        return roles.isViewer(id, user);
    }

    public Sheet get(String id) {
        return sheets.findById(id).orElseThrow(() -> new SheetNotFoundException(id));
    }

    /** The sheet's today: use it for every date-dependent decision and default date. */
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseThrow(() -> new SheetNotFoundException(id));
    }

    /** The default date of a new claim: the sheet's entry date while it is set, else its today. */
    public LocalDate entryDate(String id) {
        return get(id).entryDateOrToday();
    }

    /** Whether the sheet is archived: every change to it is refused. A sheet not started yet is not. */
    public boolean isArchived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }

    /** Archive the sheet: only a steward may, and only once. */
    @Transactional
    public Sheet archive(String id, String user) {
        Sheet sheet = sheets.findById(id).orElseThrow(() -> new SheetNotFoundException(id));
        if (sheet.isArchived()) {
            throw new SheetArchivedException(id);
        }
        if (roles.roleOf(id, user) != Role.STEWARD) {
            throw new ArchiveForbiddenException(id, user);
        }
        sheet.setArchived(true);
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + user);
        return saved;
    }

    /**
     * Change the sheet's today. A change first records a SNAPSHOT of each person's due-total on the day being left,
     * in list order, and keeps it as one of the person's snapshot lines; every open period past its grace days on the new today then closes, before the figures it moved
     * are recorded. A new today releases every claim editor's lock.
     */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = sheets.findById(id).orElseThrow(() -> new SheetNotFoundException(id));
        RollupService.Snapshot before = rollup.snapshot(id);
        boolean changed = !today.equals(sheet.getToday());
        if (changed) {
            LocalDate left = sheet.getToday();
            before.people().forEach((personId, person) -> {
                snapshots.save(new PersonSnapshot(id, personId, left, person.dueTotal()));
                audit.recordOnCommit("SNAPSHOT sheet=" + id + " by=" + user
                        + " name=" + person.name() + " due-total=" + person.dueTotal());
            });
        }
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        periods.autoClose(id, user, today, saved.getGraceDays());
        rollup.recordChanges(id, user, before);
        if (changed) {
            claims.unlockAll(id);
        }
        return saved;
    }

    /**
     * The next claim number on the sheet for a claim placed by the date: <FY>-<n>, counting from 1 within the fiscal
     * year (by the sheet's fiscal start month) the date falls in. The sheets of its tree (its root parent and all the
     * root's descendants) share one sequence per fiscal year, so n is the number after the last given in that year on
     * any of them. The rows of the tree's sheets are locked, in id order, until the caller's transaction ends, so two
     * claims opened at once get different numbers.
     */
    @Transactional
    public String nextClaimNumber(String id, LocalDate date) {
        Sheet sheet = sheets.findById(id).orElseThrow(() -> new SheetNotFoundException(id));
        SortedSet<String> tree = treeOf(id);
        for (String member : tree) {
            if (sheets.lockById(member) != 1 && member.equals(id)) {
                throw new SheetNotFoundException(id);
            }
        }
        String year = sheet.fiscalYearOf(date);
        long next = sheets.maxLastFiscalNumber(tree, year) + 1;
        sheets.setLastFiscalNumber(id, year, next);
        return year + "-" + next;
    }

    /** The ids of the sheets in the sheet's tree: its topmost ancestor and every sheet below it. */
    private SortedSet<String> treeOf(String id) {
        Set<String> seen = new HashSet<>();
        String root = id;
        seen.add(root);
        for (String parent = sheets.parentOf(root); parent != null && seen.add(parent); parent = sheets.parentOf(root)) {
            root = parent;
        }
        SortedSet<String> tree = new TreeSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            String next = todo.pop();
            if (tree.add(next)) {
                todo.addAll(sheets.childIdsOf(next));
            }
        }
        return tree;
    }

    /**
     * The next bulk settle batch number on the sheet: 1 for the first, then 2, and so on. The sheet's row is locked
     * until the caller's transaction ends, so a batch whose claims all roll back takes no number.
     */
    @Transactional
    public long nextSettleBatch(String id) {
        if (sheets.incrementLastSettleBatch(id) != 1) {
            throw new SheetNotFoundException(id);
        }
        return sheets.lastSettleBatch(id);
    }

    /** The number of the sheet's last bulk settle batch, as stored now: 0 while there has been none. */
    public long lastSettleBatch(String id) {
        return sheets.lastSettleBatch(id);
    }

    /**
     * The next place in the sheet's claim lists: a claim with a later place is listed before one with an earlier
     * place. The sheet's row is locked until the caller's transaction ends.
     */
    @Transactional
    public long nextListed(String id) {
        if (sheets.incrementLastListed(id) != 1) {
            throw new SheetNotFoundException(id);
        }
        return sheets.lastListed(id);
    }

    /**
     * The next number in a sequence of its own on the sheet (a period's, or X for no period): the key, a dash, then
     * 1 for the first, then 2, and so on. The sheet's row is locked until the caller's transaction ends.
     */
    @Transactional
    public String nextSequenceNumber(String id, String key) {
        if (sheets.lockById(id) != 1) {
            throw new SheetNotFoundException(id);
        }
        sheets.incrementSequence(id, key);
        return key + "-" + sheets.lastSequenceNumber(id, key);
    }

    private Sheet start(String id, String user) {
        try {
            return sheets.saveAndFlush(new Sheet(id, user, LocalDate.now()));
        } catch (DataIntegrityViolationException e) {
            // Another user started the same sheet at the same moment: it exists now.
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }
}
