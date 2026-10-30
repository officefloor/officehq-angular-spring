package net.officefloor.hq.app.sheet;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.asof.SheetPasts;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.statement.DayEnds;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Loads a sheet by id, starting an empty one when the id was never used, and keeps the sheet's
 * today. The real clock is read only to start a new sheet's today; everything else that depends
 * on the date asks {@link #today(String)}.
 */
@Service
public class SheetService {

    private final SheetRepository sheets;
    private final SheetSettingRepository settings;
    private final Rollup rollup;
    private final DayEnds dayEnds;
    private final SheetPasts pasts;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemDefaultZone();

    public SheetService(SheetRepository sheets, SheetSettingRepository settings, Rollup rollup, DayEnds dayEnds,
            SheetPasts pasts, ApplicationEventPublisher events, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.dayEnds = dayEnds;
        this.pasts = pasts;
        this.sheets = sheets;
        this.settings = settings;
        this.rollup = rollup;
        this.events = events;
    }

    public Sheet load(String id, String user) {
        try {
            return loadOrCreate(id, user);
        } catch (DataIntegrityViolationException e) {
            // Another user started the same sheet at the same moment: theirs is the one.
            return sheets.findById(id).orElseThrow(() -> e);
        }
    }

    /** The sheet as it stands; refused (404) when it was never loaded. */
    public Sheet get(String id) {
        return sheets.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet"));
    }

    /** The sheet's dated settings: the floor, late percent, terms, clerk limit and cap in force on any date. */
    public DatedSettings settings(String id) {
        return new DatedSettings(settings.findBySheetId(id));
    }

    /**
     * The ids of the sheet's descendants: its children (the sheets naming it as their parent), their
     * children, and so on, each once, nearest first.
     */
    public List<String> descendants(String id) {
        Set<String> seen = new LinkedHashSet<>(List.of(id));
        Deque<String> next = new ArrayDeque<>(List.of(id));
        while (!next.isEmpty()) {
            String sheet = next.poll();
            for (SheetSetting s : settings.findByKeyAndValue(DatedSettings.PARENT, sheet)) {
                if (sheet.equals(settings(s.getSheetId()).parent()) && seen.add(s.getSheetId())) {
                    next.add(s.getSheetId());
                }
            }
        }
        return seen.stream().skip(1).toList();
    }

    /**
     * The ids of the sheets of the sheet's tree, among the sheets that exist: the sheet itself, its ancestors
     * (its parent, the parent's parent, and so on) and its descendants, each once.
     */
    public List<String> tree(String id) {
        Set<String> seen = new LinkedHashSet<>(List.of(id));
        for (String up = settings(id).parent(); up != null && sheets.existsById(up) && seen.add(up);
                up = settings(up).parent()) {
        }
        seen.addAll(descendants(id));
        return List.copyOf(seen);
    }

    /** The sheet's policy date as set; null when blank (the sheet's today is the policy date then). */
    public LocalDate policyDate(String id) {
        return sheets.findById(id).map(Sheet::getPolicyDate).orElse(null);
    }

    /** The sheet's period numbering from as set; null when blank (off). */
    public LocalDate periodNumberingFrom(String id) {
        return sheets.findById(id).map(Sheet::getPeriodNumberingFrom).orElse(null);
    }

    /** The sheet's entry date as set; null when blank (off). */
    public LocalDate entryDate(String id) {
        return sheets.findById(id).map(Sheet::getEntryDate).orElse(null);
    }

    /** The date a new claim takes when none is given: the sheet's entry date while set, else its today. */
    public LocalDate claimDate(String id) {
        LocalDate entry = entryDate(id);
        return entry != null ? entry : today(id);
    }

    /** The sheet's grace days as set; null when blank (off). */
    public Integer graceDays(String id) {
        return sheets.findById(id).map(Sheet::getGraceDays).orElse(null);
    }

    /** Whether the sheet is archived: every change to it is refused. */
    public boolean isArchived(String id) {
        return sheets.findById(id).map(Sheet::isArchived).orElse(false);
    }

    /** The sheet's today: the date to use for every decision and default date on the sheet. */
    public LocalDate today(String id) {
        return sheets.findById(id).map(Sheet::getToday).orElseGet(() -> LocalDate.now(clock));
    }

    /**
     * The next claim number on the sheet within the fiscal year: the sheets of a tree share one sequence per
     * fiscal year, so the next after the last number given in that year on any sheet of the sheet's tree (1 for
     * the first). Two users taking one at the same moment wait on each other, so no number is given twice or
     * skipped.
     */
    @Transactional
    public int nextClaimNumber(String id, String fiscalYear) {
        List<String> tree = new ArrayList<>(tree(id));
        Collections.sort(tree);
        for (String sheetId : tree) {
            if (sheets.lockById(sheetId) == null && sheetId.equals(id)) {
                throw new IllegalStateException("No such sheet " + id);
            }
        }
        String in = String.join(",", Collections.nCopies(tree.size(), "?"));
        List<Object> args = new ArrayList<>(tree);
        args.add(fiscalYear);
        Integer last = jdbc.queryForObject("SELECT COALESCE(MAX(last_number), 0) FROM sheet_fiscal_number"
                + " WHERE sheet_id IN (" + in + ") AND fiscal_year = ?", Integer.class, args.toArray());
        int next = (last == null ? 0 : last) + 1;
        jdbc.update("MERGE INTO sheet_fiscal_number (sheet_id, fiscal_year, last_number) KEY (sheet_id, fiscal_year)"
                + " VALUES (?, ?, ?)", id, fiscalYear, next);
        return next;
    }

    /**
     * Take the sheet's next settle batch number (1, 2, ...); two bulk settles at the same moment wait on each
     * other, so no number is given twice or skipped.
     */
    @Transactional
    public int nextSettleBatch(String id) {
        if (sheets.bumpSettleBatch(id) != 1) {
            throw new IllegalStateException("No such sheet " + id);
        }
        return sheets.lastSettleBatch(id);
    }

    /** The number of the sheet's last settle batch; 0 when there has been none (or no such sheet). */
    public int lastSettleBatch(String id) {
        Integer last = sheets.lastSettleBatch(id);
        return last == null ? 0 : last;
    }

    /**
     * Change the sheet's today, starting the sheet if it was never loaded. The change has no audit
     * line of its own, only what follows from it ({@link TodayChanged}: the periods past their grace
     * days are closed) and then the figures it moves. The sheet as it stands is first written down as at
     * the end of the today it leaves (see {@link DayEnds} and {@link SheetPasts}; the sheet as of that day
     * holds no snapshot line for it), and everyone's due-total on it is announced first ({@link DaySnapshot}), preceded
     * by everyone's score on it while the score log is on ({@link DayScores}).
     */
    @Transactional
    public Sheet setToday(String id, String user, LocalDate today) {
        Sheet sheet = load(id, user);
        Rollup.Figures before = rollup.figures(id);
        if (!today.equals(sheet.getToday())) {
            pasts.record(id, sheet.getToday());
            if (new DatedSettings(settings.findBySheetId(id)).scoreLog()) {
                events.publishEvent(new DayScores(id, user, sheet.getToday(), rollup.scores(id)));
            }
            events.publishEvent(new DaySnapshot(id, user, sheet.getToday(), new LinkedHashMap<>(before.people())));
            dayEnds.record(id, sheet.getToday());
        }
        sheet.setToday(today);
        Sheet saved = sheets.saveAndFlush(sheet);
        events.publishEvent(new TodayChanged(id, user, today));
        rollup.changed(id, user, before);
        return saved;
    }

    private Sheet loadOrCreate(String id, String user) {
        return sheets.findById(id)
                .orElseGet(() -> sheets.saveAndFlush(new Sheet(id, user, LocalDate.now(clock))));
    }
}
