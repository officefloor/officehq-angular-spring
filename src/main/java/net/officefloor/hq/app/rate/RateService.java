package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.grade.GradeService;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates set on a sheet for each grade, each in force from its from-date. A grade with no rate
 *  in force on a date on a sheet takes its parent sheet's, then the parent's parent's, up to
 *  {@link #PARENT_LEVELS} levels up; with none there either, a sheet that was cloned takes the rate
 *  of the sheet it was cloned from, and so on up that sheet's parents and source. */
@Service
public class RateService {

    /** How many levels of parent sheets are looked to for a rate the sheet has none in force for. */
    public static final int PARENT_LEVELS = 3;

    private final RateRepository rates;
    private final RateVersionRepository versions;
    private final SheetService sheets;
    private final SheetRepository sheetRepository;
    private final History history;
    private final Audit audit;
    private final JdbcTemplate jdbc;
    private final GradeService grades;
    private final SettingService settings;

    public RateService(RateRepository rates, RateVersionRepository versions, SheetService sheets, SheetRepository sheetRepository, History history,
            Audit audit, JdbcTemplate jdbc, GradeService grades, SettingService settings) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.grades = grades;
        this.rates = rates;
        this.versions = versions;
        this.sheets = sheets;
        this.sheetRepository = sheetRepository;
        this.history = history;
        this.audit = audit;
    }

    /** The sheet's parent sheet's id as it stood at the end of a day (null: as it stands now); null
     *  when it had none. A sheet whose parent was never kept in its history has it as it stands now. */
    private String parentOf(String sheetId, LocalDate asOf) {
        if (asOf != null && history.settingsKept(sheetId).contains(SettingService.PARENT)) {
            return history.settings(sheetId, asOf).get(SettingService.PARENT);
        }
        return sheetRepository.findById(sheetId).map(Sheet::getParentId).orElse(null);
    }

    /** The sheets the sheet's rates fall back to, nearest first: its parent, the parent's parent, up
     *  to {@link #PARENT_LEVELS} levels up. */
    public List<String> ancestors(String sheetId) {
        return ancestors(sheetId, null);
    }

    /** The sheets the sheet's rates fall back to as they stood at the end of a day (null: now),
     *  nearest first: the parent each had then. */
    public List<String> ancestors(String sheetId, LocalDate asOf) {
        List<String> found = new ArrayList<>();
        String sheet = parentOf(sheetId, asOf);
        while (sheet != null && found.size() < PARENT_LEVELS && !sheet.equals(sheetId) && !found.contains(sheet)) {
            found.add(sheet);
            sheet = parentOf(sheet, asOf);
        }
        return found;
    }

    /** Every sheet the sheet's rates fall back to, nearest first: its {@link #ancestors} as they stood
     *  at the end of a day (null: now); then, for a sheet that was cloned, the sheet it was cloned from
     *  and that sheet's own fall-backs as they stand now. A sheet is listed once, the sheet itself never. */
    public List<String> fallbacks(String sheetId, LocalDate asOf) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>(Set.of(sheetId));
        String sheet = sheetId;
        LocalDate day = asOf;
        while (sheet != null) {
            for (String ancestor : ancestors(sheet, day)) {
                if (seen.add(ancestor)) {
                    found.add(ancestor);
                }
            }
            String source = sheetRepository.findById(sheet).map(Sheet::getClonedFrom).orElse(null);
            if (source == null || !seen.add(source)) {
                break;
            }
            found.add(source);
            sheet = source;
            day = null;
        }
        return found;
    }

    /** The sheet's rates by grade, each grade's from the earliest. */
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromAscIdAsc(sheetId);
    }

    /** A rate's older versions, newest first: as they were read with it, else from the kept versions. */
    public List<Rate> earlier(Rate rate) {
        if (rate.getEarlier() != null) {
            return rate.getEarlier();
        }
        List<RateVersion> older = versions.findByRateIdOrderByVersionAsc(rate.getId());
        List<Rate> earlier = new ArrayList<>();
        for (int i = older.size() - 1; i >= 0; i--) {
            RateVersion version = older.get(i);
            earlier.add(rate.asVersion(version.getRate(), version.getTo(), version.getVersion(),
                    version.getForwardAfter()));
        }
        rate.setEarlier(earlier);
        return earlier;
    }

    /** The sheet's rates by grade, each grade's from the earliest, as they stood at the end of a day
     *  (null: as they stand now). */
    public List<Rate> list(String sheetId, LocalDate asOf) {
        if (asOf == null) {
            return list(sheetId);
        }
        return grades.grades(sheetId).stream()
                .flatMap(grade -> history.rates(sheetId, grade, asOf).stream()
                        .sorted(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder()))))
                .toList();
    }

    /** The grade's rate in force on a date: the one whose from-date is the latest on or before it
     *  (a rate with no from-date applies from the beginning of time); none applies when there is no
     *  such rate, or when that one's to-date is before the date. When none applies on the sheet, the
     *  parent sheet's is taken, then the parent's parent's, up to {@link #PARENT_LEVELS} levels up,
     *  then those of the sheet it was cloned from (see {@link #fallbacks}); empty when none applies on
     *  any of them. */
    public Optional<Rate> rateOn(String sheetId, String grade, LocalDate date) {
        return rateOn(sheetId, grade, date, null);
    }

    /** The grade's rate in force on a date as the sheet's rates stood at the end of a day (null: as
     *  they stand now), falling back to the rates of the parent sheets it had then, as they stand now. */
    public Optional<Rate> rateOn(String sheetId, String grade, LocalDate date, LocalDate asOf) {
        return rateFor(sheetId, grade, date, null, asOf);
    }

    /** The grade's rate that applies to a claim (null: to no claim in particular) on a date, as
     *  {@link #rateOn} finds it, but of the rates that apply to the claim only: a forward-only rate
     *  saved before the claim was added is passed over, the claim keeping the version of it, if any,
     *  that applied to it (see {@link Rate#appliesTo}). */
    public Optional<Rate> rateFor(String sheetId, String grade, LocalDate date, Long claimId, LocalDate asOf) {
        Optional<Rate> own = ownRateFor(sheetId, grade, date, claimId, asOf);
        if (own.isPresent()) {
            return own;
        }
        // Read as of a day, the sheet falls back to the parents it had at the end of it, their rates as
        // they stand now: a parent keeps its own today, so its history does not follow the sheet's.
        for (String ancestor : fallbacks(sheetId, asOf)) {
            Optional<Rate> theirs = ownRateFor(ancestor, grade, date, claimId, null);
            if (theirs.isPresent()) {
                return theirs;
            }
        }
        return Optional.empty();
    }

    private Optional<Rate> ownRateFor(String sheetId, String grade, LocalDate date, Long claimId, LocalDate asOf) {
        List<Rate> theirs = asOf == null ? rates.findBySheetIdAndGradeOrderByFromAsc(sheetId, grade)
                : history.rates(sheetId, grade, asOf);
        return inForce(theirs.stream().map(rate -> versionFor(rate, claimId)).flatMap(Optional::stream).toList(),
                date);
    }

    /** The newest version of a rate that applies to a claim; empty when none of them does. */
    private Optional<Rate> versionFor(Rate rate, Long claimId) {
        if (rate.appliesTo(claimId)) {
            return Optional.of(rate);
        }
        return earlier(rate).stream().filter(version -> version.appliesTo(claimId)).findFirst();
    }

    private static Optional<Rate> inForce(List<Rate> theirs, LocalDate date) {
        return theirs.stream()
                .filter(rate -> rate.appliesOn(date))
                .max(Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(rate -> !rate.endedBy(date));
    }

    /** Set the rate for a grade from a date (null: from the beginning of time) through a date (null:
     *  with no end). When the grade has a rate from that date, the value it held is kept as an older
     *  version and the new one becomes its newest version, the one in force. A rate from a date on or
     *  after the sheet's today is forward-only: it applies only to claims added after it is saved. A
     *  to-date before the from-date is refused. */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal rate) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new RateRefusedException("A rate's to-date cannot be before its from-date.");
        }
        sheets.load(sheetId);
        if (!grades.isGrade(sheetId, grade)) {
            throw new RateRefusedException("There is no grade " + grade);
        }
        BigDecimal scaled = rate.setScale(2, RoundingMode.UNNECESSARY);
        Long forwardAfter = from != null && !from.isBefore(sheets.today(sheetId))
                ? jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM claim", Long.class)
                : null;
        Optional<Rate> had = from == null
                ? rates.findBySheetIdAndGradeAndFromIsNull(sheetId, grade)
                : rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from);
        Rate saved = had
                .map(existing -> {
                    versions.save(new RateVersion(existing, sheets.today(sheetId), user));
                    existing.newVersion(scaled, to, forwardAfter);
                    return existing;
                })
                .orElseGet(() -> {
                    Rate made = new Rate(sheetId, grade, from, to, scaled);
                    made.setForwardAfter(forwardAfter);
                    return made;
                });
        saved = rates.saveAndFlush(saved);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade
                + " rate=" + saved.getRate().toPlainString() + (from != null ? " from=" + from : "")
                + (to != null ? " to=" + to : ""));
        return saved;
    }

    /** A steward withdraws the newest version of a grade's rate from a date (null: from the beginning
     *  of time): the version before it, when there is one, is in force again; with none the rate is
     *  gone. Anyone else, or a grade with no rate from that date, is refused. */
    @Transactional
    public void withdraw(String sheetId, String user, String grade, LocalDate from) {
        sheets.load(sheetId);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new RateRefusedException(user + " is not a steward: only a steward withdraws a rate");
        }
        Rate rate = (from == null
                ? rates.findBySheetIdAndGradeAndFromIsNull(sheetId, grade)
                : rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from))
                .orElseThrow(() -> new RateRefusedException("Grade " + grade + " has no rate from that date"));
        List<RateVersion> older = versions.findByRateIdOrderByVersionAsc(rate.getId());
        if (older.isEmpty()) {
            history.rateWithdrawn(rate);
            rates.delete(rate);
            rates.flush();
        } else {
            RateVersion previous = older.get(older.size() - 1);
            versions.delete(previous);
            rate.restore(previous.getRate(), previous.getTo(), previous.getVersion(), previous.getForwardAfter());
            rates.saveAndFlush(rate);
        }
        audit.recordOnCommit("RATE_WITHDRAWN sheet=" + sheetId + " by=" + user + " grade=" + grade
                + (from != null ? " from=" + from : ""));
    }
}
