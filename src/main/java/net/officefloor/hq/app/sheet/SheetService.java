package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads a sheet by id; a sheet never loaded before starts empty. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final RollupService rollup;
    private final ApplicationEventPublisher events;

    public SheetService(SheetRepository sheets, RollupService rollup, ApplicationEventPublisher events) {
        this.sheets = sheets;
        this.rollup = rollup;
        this.events = events;
    }

    public Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> create(id));
    }

    /**
     * Change the sheet's today; every date decision on the sheet follows it. It has no audit line of its own, only
     * the figures it moves, after a SNAPSHOT line per person with their due-total on the day being left. What follows
     * a today change (the periods it closes) runs before those are rolled up.
     */
    @Transactional
    public Sheet setToday(String by, String id, LocalDate today) {
        Sheet sheet = load(id);
        RollupService.Snapshot before = rollup.snapshot(id);
        rollup.recordSnapshot(by, id, before, sheet.getToday());
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        events.publishEvent(new TodayChanged(by, saved));
        rollup.recordChanges(by, id, before);
        return saved;
    }

    /** Loads the sheet and locks it until the transaction ends, so changes checked against the whole sheet run in turn. */
    @Transactional
    public Sheet lock(String id) {
        load(id);
        return sheets.findWithLockById(id).orElseThrow();
    }

    /**
     * Takes the sheet's next claim number in the fiscal year: the one after the last given in that year on any sheet of
     * its tree, which share one sequence per fiscal year. The tree's root is locked, so two callers on the tree at once
     * never get the same one.
     */
    @Transactional
    public long nextFiscalClaimNumber(String id, int fiscalYear) {
        load(id);
        Set<String> tree = tree(id);
        sheets.findWithLockById(root(id)).orElseThrow();
        long next = sheets.lastFiscalClaimNumberOf(tree, fiscalYear) + 1;
        sheets.setFiscalClaimNumber(id, fiscalYear, next);
        return next;
    }

    /** The top of the sheet's tree: its parent, their parent and so on, as far as the sheets exist. */
    private String root(String id) {
        Set<String> seen = new HashSet<>();
        String at = id;
        while (seen.add(at)) {
            String parent = sheets.findById(at).map(Sheet::getParentId).orElse(null);
            if (parent == null || !sheets.existsById(parent)) {
                break;
            }
            at = parent;
        }
        return at;
    }

    /** Every sheet of the sheet's tree, the sheet itself among them: its root and all the sheets below it. */
    private Set<String> tree(String id) {
        String root = root(id);
        Set<String> tree = new LinkedHashSet<>(List.of(root));
        ArrayDeque<String> toVisit = new ArrayDeque<>(List.of(root));
        while (!toVisit.isEmpty()) {
            for (Sheet child : sheets.findByParentIdOrderByIdAsc(toVisit.poll())) {
                if (tree.add(child.getId())) {
                    toVisit.add(child.getId());
                }
            }
        }
        tree.add(id);
        return tree;
    }

    /** Takes the sheet's next settle batch number; two callers at once never get the same one. */
    @Transactional
    public int nextSettleBatch(String id) {
        load(id);
        sheets.incrementSettleBatch(id);
        return sheets.lastSettleBatch(id);
    }

    private Sheet create(String id) {
        try {
            return sheets.saveAndFlush(new Sheet(id));
        } catch (DataIntegrityViolationException e) {
            // another user created it at the same moment
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }
}
