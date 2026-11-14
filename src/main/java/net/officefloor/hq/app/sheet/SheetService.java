package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.Audit;
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

    /** The sheet's today: the date every date-dependent decision and default on the sheet uses. */
    public LocalDate today(String id) {
        return load(id).getToday();
    }

    /** Take the sheet's next claim number, holding the sheet locked until the transaction ends so
     *  claims opened at the same moment get different numbers. */
    @Transactional
    public long nextClaimNumber(String id) {
        load(id);
        Sheet sheet = sheets.findLockedById(id).orElseThrow();
        long number = sheet.nextClaimNumber();
        sheets.saveAndFlush(sheet);
        return number;
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
