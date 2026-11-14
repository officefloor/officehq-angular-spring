package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.Preview;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads sheets by id; a sheet never loaded before starts empty, its today the real date. */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final Clock clock;
    private final Audit audit;

    public SheetService(SheetRepository sheets, Clock clock, Audit audit) {
        this.sheets = sheets;
        this.clock = clock;
        this.audit = audit;
    }

    /** The sheet with this id, created empty when it was never loaded before. */
    public Sheet load(String id) {
        return sheets.findById(id).orElseGet(() -> {
            try {
                return sheets.saveAndFlush(new Sheet(id, LocalDate.now(clock)));
            } catch (DataIntegrityViolationException e) {
                return sheets.findById(id).orElseThrow(() -> e); // created concurrently
            }
        });
    }

    /** The sheet's today: the date every date-dependent decision and default on the sheet uses. While
     *  a read is answered for a screen set to a preview date, that date stands in for it. */
    public LocalDate today(String id) {
        LocalDate today = load(id).getToday();
        return Preview.current().orElse(today);
    }

    /** Take the sheet's next claim number, FY<year>-<n>: the one after the last given in the fiscal
     *  year (under the sheet's start month) of the date anywhere in its tree (the sheets reached
     *  through parents and children from it). Every sheet of the tree is held locked until the
     *  transaction ends so claims opened at the same moment get different numbers. */
    @Transactional
    public String nextClaimNumber(String id, LocalDate date) {
        load(id);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        int fiscalYear = sheet.fiscalYearOf(date);
        long lastInTree = 0;
        for (String other : treeOf(id)) {
            if (!other.equals(id)) {
                lastInTree = Math.max(lastInTree,
                        sheets.findLockedById(other).map(s -> s.getLastClaimNumber(fiscalYear)).orElse(0L));
            }
        }
        long number = sheet.takeClaimNumberAfter(fiscalYear, lastInTree);
        sheets.saveAndFlush(sheet);
        return "FY" + String.format("%04d", fiscalYear) + "-" + number;
    }

    /** The ids of the sheets in the sheet's tree, the sheet included, in id order: its topmost
     *  ancestor and every sheet below it. */
    private List<String> treeOf(String id) {
        Set<String> seen = new HashSet<>();
        String root = id;
        seen.add(root);
        String parent = sheets.findById(root).map(Sheet::getParentId).orElse(null);
        while (parent != null && seen.add(parent)) {
            root = parent;
            parent = sheets.findById(root).map(Sheet::getParentId).orElse(null);
        }
        Set<String> tree = new HashSet<>();
        List<String> todo = new ArrayList<>(List.of(root));
        while (!todo.isEmpty()) {
            String next = todo.remove(todo.size() - 1);
            if (tree.add(next)) {
                sheets.findByParentIdOrderByIdAsc(next).forEach(child -> todo.add(child.getId()));
            }
        }
        List<String> ids = new ArrayList<>(tree);
        ids.sort(null);
        return ids;
    }

    /** Take the sheet's next settle batch number, holding the sheet locked until the transaction
     *  ends so bulk settles made at the same moment get different numbers. */
    @Transactional
    public long nextBatchNumber(String id) {
        load(id);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        long number = sheet.nextBatchNumber();
        sheets.saveAndFlush(sheet);
        return number;
    }

    /** Hold the sheet locked until the transaction ends, so changes to it made at the same moment
     *  wait their turn. */
    @Transactional
    public void lock(String id) {
        load(id);
        sheets.findLockedById(id).orElseThrow();
    }

    /** Change the sheet's today. */
    @Transactional
    public Sheet setToday(String id, LocalDate today) {
        Sheet sheet = load(id);
        sheet.setToday(today);
        return sheets.save(sheet);
    }

    /** The ids of the sheet's descendants: its children (the sheets naming it as their parent), their
     *  children, and so on; the sheet itself is not among them. */
    public List<String> descendants(String id) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>(Set.of(id));
        List<String> next = List.of(id);
        while (!next.isEmpty()) {
            List<String> children = new ArrayList<>();
            for (String parent : next) {
                for (Sheet child : sheets.findByParentIdOrderByIdAsc(parent)) {
                    if (seen.add(child.getId())) {
                        children.add(child.getId());
                    }
                }
            }
            found.addAll(children);
            next = children;
        }
        return found;
    }

    /** The ids of the other sheets of the sheet's tree: its ancestors (its parent, the parent's parent,
     *  and so on) and its descendants, among the sheets that exist; the sheet itself is not among them. */
    public List<String> tree(String id) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>(Set.of(id));
        for (String up = sheets.findById(id).map(Sheet::getParentId).orElse(null); up != null && seen.add(up);
                up = sheets.findById(up).map(Sheet::getParentId).orElse(null)) {
            if (sheets.existsById(up)) {
                found.add(up);
            }
        }
        for (String down : descendants(id)) {
            if (!found.contains(down)) {
                found.add(down);
            }
        }
        return found;
    }

    /** The ids of the other sheets of the whole tree the sheet is in: its root (the topmost ancestor
     *  that exists) and all the root's descendants, by sheet id; the sheet itself is not among them. */
    public List<String> wholeTree(String id) {
        String root = id;
        Set<String> seen = new HashSet<>(Set.of(id));
        for (String up = sheets.findById(id).map(Sheet::getParentId).orElse(null); up != null && seen.add(up);
                up = sheets.findById(up).map(Sheet::getParentId).orElse(null)) {
            if (sheets.existsById(up)) {
                root = up;
            }
        }
        Set<String> found = new TreeSet<>(descendants(root));
        found.add(root);
        found.remove(id);
        return new ArrayList<>(found);
    }

    /** Whether a sheet with this id was ever used (loaded or changed). */
    public boolean exists(String id) {
        return sheets.existsById(id);
    }

    /** Whether the sheet is archived: every change to it is then refused. */
    public boolean isArchived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }

    /** Archive the sheet; refused when it is archived already. */
    @Transactional
    public Sheet archive(String id, String by) {
        load(id);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        if (sheet.isArchived()) {
            throw new ArchiveRefusedException("the sheet is archived already");
        }
        sheet.archive();
        Sheet saved = sheets.saveAndFlush(sheet);
        audit.recordOnCommit("SHEET_ARCHIVED sheet=" + id + " by=" + by);
        return saved;
    }
}
