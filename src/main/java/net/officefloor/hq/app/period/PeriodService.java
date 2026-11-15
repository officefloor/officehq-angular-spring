package net.officefloor.hq.app.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Permission;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PeriodService {

    /** An opening balance as a pasted line gives it: two decimals, 0.00 or more. */
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,13}\\.\\d{2}");

    private final PeriodRepository periods;
    private final PersonRepository people;
    private final SheetService sheets;
    private final RollupService rollup;
    private final SettingsService settings;
    private final HistoryService history;
    private final Audit audit;
    private final LastRolloverRepository rollovers;
    private final ClaimRepository claims;

    public PeriodService(PeriodRepository periods, PersonRepository people, SheetService sheets, RollupService rollup,
            SettingsService settings, HistoryService history, Audit audit, LastRolloverRepository rollovers,
            ClaimRepository claims) {
        this.rollovers = rollovers;
        this.claims = claims;
        this.periods = periods;
        this.people = people;
        this.sheets = sheets;
        this.rollup = rollup;
        this.settings = settings;
        this.history = history;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Period> list(String sheetId) {
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** Add a period to the sheet (the sheet is created first if it was never loaded; a from-date left out is the sheet's today),
     *  with an optional cap on each person's OPEN or PENDING claims in it (null for none); refused when
     *  its from-date is after its to-date, its cap is below 1, its name is in use on the sheet or it overlaps another. */
    @Transactional
    public Period add(String sheetId, String user, String name, LocalDate fromOrNull, LocalDate to, Integer cap) {
        sheets.load(sheetId, user);
        LocalDate from = fromOrNull != null ? fromOrNull : sheets.today(sheetId);
        // held until the transaction ends, so two periods added at once are checked one after the other
        sheets.lock(sheetId);
        String trimmed = name.trim();
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The from-date is after the to-date");
        }
        if (cap != null && cap < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The cap must be a whole number of 1 or more");
        }
        List<Period> existing = periods.findBySheetIdOrderByFromAsc(sheetId);
        if (existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(trimmed))) {
            throw nameInUse();
        }
        if (existing.stream().anyMatch(p -> p.overlaps(from, to))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period overlaps another");
        }
        Period period;
        try {
            Period fresh = new Period(sheetId, trimmed, from, to);
            fresh.setCap(cap);
            period = periods.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        }
        audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user
                + " period=" + trimmed + " from=" + from + " to=" + to + (cap == null ? "" : " cap=" + cap));
        return period;
    }

    /** Generate one period per calendar month from the from-date's month (a from-date left out is
     *  the sheet's today) to the to-date's month, each from the first to the last day of its month
     *  and named yyyy-mm; a month whose name is already a period's, or whose days overlap a period
     *  on the sheet, is left out. Refused when the to-date is before the from-date. Returns the
     *  periods made, by from-date. */
    @Transactional
    public List<Period> generate(String sheetId, String user, LocalDate fromOrNull, LocalDate to) {
        sheets.load(sheetId, user);
        LocalDate from = fromOrNull != null ? fromOrNull : sheets.today(sheetId);
        sheets.lock(sheetId);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The to-date is before the from-date");
        }
        List<Span> spans = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            spans.add(new Span(month.toString(), month.atDay(1), month.atEndOfMonth()));
        }
        return addAll(sheetId, user, spans);
    }

    /** A period to be generated: its name and its days. */
    private record Span(String name, LocalDate first, LocalDate last) {
    }

    /** Add each span as a period, in order, leaving out one whose name is already a period's or
     *  whose days overlap a period on the sheet; the sheet is loaded and locked by the caller.
     *  Returns the periods made. */
    private List<Period> addAll(String sheetId, String user, List<Span> spans) {
        List<Period> existing = new ArrayList<>(periods.findBySheetIdOrderByFromAsc(sheetId));
        List<Period> made = new ArrayList<>();
        for (Span span : spans) {
            if (existing.stream().anyMatch(p -> p.getName().equalsIgnoreCase(span.name()) || p.overlaps(span.first(), span.last()))) {
                continue;
            }
            Period period = periods.saveAndFlush(new Period(sheetId, span.name(), span.first(), span.last()));
            existing.add(period);
            made.add(period);
            audit.recordOnCommit("PERIOD_ADDED sheet=" + sheetId + " by=" + user
                    + " period=" + span.name() + " from=" + span.first() + " to=" + span.last());
        }
        return made;
    }

    /** Generate the four calendar quarters of a year, named &lt;yyyy&gt;-Q1 to &lt;yyyy&gt;-Q4, each
     *  as {@link #add} would add it: a quarter whose name is already a period's, or whose days
     *  overlap a period on the sheet, is left out. Returns the periods made, by from-date. */
    @Transactional
    public List<Period> generateQuarters(String sheetId, String user, int year) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        List<Span> spans = new ArrayList<>();
        for (int q = 1; q <= 4; q++) {
            YearMonth first = YearMonth.of(year, q * 3 - 2);
            spans.add(new Span(year + "-Q" + q, first.atDay(1), first.plusMonths(2).atEndOfMonth()));
        }
        return addAll(sheetId, user, spans);
    }

    /** Generate the twelve monthly periods of a fiscal year, named FY&lt;yyyy&gt; by the calendar year
     *  its first day falls in: from the first of the sheet's fiscal start month in that year to
     *  the end of the eleventh month after it, exactly as {@link #generate} does. */
    @Transactional
    public List<Period> generateFiscalYear(String sheetId, String user, int year) {
        sheets.load(sheetId, user);
        int startMonth = settings.fiscalStartMonth(sheetId);
        YearMonth first = YearMonth.of(year, startMonth);
        return generate(sheetId, user, first.atDay(1), first.plusMonths(11).atEndOfMonth());
    }

    /** A year rolled over: the new fiscal year's periods it made, by from-date, and the new year's
     *  first period when openings were carried into it (null when none were). */
    public record Rollover(List<Period> made, Period opened) {
    }

    /** Roll a fiscal year over: generate the next fiscal year's twelve periods as
     *  {@link #generateFiscalYear} does, then add each person's open figure at the end of the old
     *  year's last period (the dues of their counting claims due inside a period of the old year
     *  and still OPEN or PENDING at that period's end) to their opening balance for the new year's
     *  first period (unless the sheet's last rollover, not undone, was of the same year: its openings
     *  are carried already), and soft-close each old-year period still open, by from-date. Kept as the sheet's last rollover, for {@link #undoRollover}. Refused
     *  (403) unless the user is a steward, and (409) when the old year has no periods on the sheet. */
    @Transactional
    public Rollover rollover(String sheetId, String user, int year) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may roll the year over");
        }
        int startMonth = settings.fiscalStartMonth(sheetId);
        LocalDate oldFirst = YearMonth.of(year, startMonth).atDay(1);
        LocalDate newFirst = oldFirst.plusYears(1);
        List<Period> old = periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> !p.getFrom().isBefore(oldFirst) && p.getFrom().isBefore(newFirst))
                .toList();
        if (old.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "FY" + year + " has no periods");
        }
        LocalDate oldEnd = old.stream().map(Period::getTo).max(Comparator.naturalOrder()).orElseThrow();
        // the claims carried out of the old year: due inside one of its periods, still open at its end
        Map<Long, BigDecimal> open = rollup.carriedByPersonInto(sheetId, oldEnd.plusDays(1), oldEnd, date -> old.stream()
                .filter(p -> p.contains(date)).findFirst().map(Period::getTo).orElse(null));
        List<Period> made = generateFiscalYear(sheetId, user, year + 1);
        LocalDate newEnd = newFirst.plusYears(1);
        Period first = periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> !p.getFrom().isBefore(newFirst) && p.getFrom().isBefore(newEnd))
                .findFirst().orElse(null);
        Period opened = null;
        Map<Long, BigDecimal> added = new LinkedHashMap<>();
        LastRollover last = rollovers.findById(sheetId).orElseGet(() -> new LastRollover(sheetId));
        // a year rolled over again, its last rollover not undone, has had its openings carried already
        boolean carried = last.getMadeBy() != null && last.getFy() == year && !last.isUndone();
        if (first != null && !carried) {
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
                BigDecimal amount = open.get(person.getId());
                if (amount == null || amount.signum() == 0) continue;
                amount = amount.setScale(2);
                first.addOpening(person.getId(), amount);
                added.put(person.getId(), amount);
                opened = first;
                audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                        + " period=" + first.getName() + " amount=" + amount.toPlainString());
            }
            periods.flush();
        }
        Set<Long> madeIds = new LinkedHashSet<>();
        made.forEach(p -> madeIds.add(p.getId()));
        last.remember(year, user, sheets.today(sheetId), madeIds, opened == null ? null : opened.getId(), added);
        rollovers.saveAndFlush(last);
        audit.recordOnCommit("ROLLOVER sheet=" + sheetId + " by=" + user + " fy=FY" + year + " periods=" + made.size());
        // the old year's periods still open are soft-closed, by from-date
        old.stream().filter(p -> !p.isClosed() && !p.isSoftClosed()).forEach(p -> softCloseNow(sheetId, user, p));
        return new Rollover(made, opened);
    }

    /** Undo the sheet's last rollover: remove the periods it generated and take back the amounts it
     *  added to opening balances. Refused (403) unless the user is a steward and the one who made
     *  it, and (409) when there is none still to undo, the sheet's today is no longer the one it
     *  was made on, or a claim is now dated or due inside a period it generated. Returns the
     *  sheet's periods after it, by from-date. */
    @Transactional
    public List<Period> undoRollover(String sheetId, String user) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may undo a rollover");
        }
        LastRollover last = rollovers.findById(sheetId).filter(r -> !r.isUndone())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "There is no rollover to undo"));
        if (!last.getMadeBy().equals(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the steward who rolled the year over may undo it");
        }
        if (!last.getMadeOn().equals(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A rollover can only be undone on the day it was made");
        }
        List<Period> made = periods.findAllById(last.getPeriodIds());
        for (Period period : made) {
            if (claims.existsDatedOrDueBetween(sheetId, period.getFrom(), period.getTo())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A claim now belongs to period " + period.getName());
            }
        }
        if (last.getOpenedPeriodId() != null && !last.getPeriodIds().contains(last.getOpenedPeriodId())) {
            periods.findByIdAndSheetId(last.getOpenedPeriodId(), sheetId).ifPresent(opened -> {
                last.getOpenings().forEach((personId, amount) -> {
                    BigDecimal left = opened.getOpenings().getOrDefault(personId, BigDecimal.ZERO).subtract(amount);
                    if (left.signum() == 0) opened.getOpenings().remove(personId);
                    else opened.getOpenings().put(personId, left);
                });
            });
        }
        periods.deleteAll(made);
        periods.flush();
        last.markUndone();
        rollovers.flush();
        audit.recordOnCommit("ROLLOVER_UNDONE sheet=" + sheetId + " by=" + user + " fy=FY" + last.getFy());
        return periods.findBySheetIdOrderByFromAsc(sheetId);
    }

    /** One pasted opening balance: the person and period it names and its amount. */
    private record Opening(Person person, Period period, BigDecimal amount) {
    }

    /** The periods an opening paste changed, by from-date, and how many lines went through. */
    public record Openings(List<Period> periods, int count) {
    }

    /** Add each line of name,period,amount to the person's opening balance for the period, in
     *  order: a person on the sheet by name or alias, a period on the sheet by name (as names are
     *  compared) and an amount of two decimals, 0.00 or more; blank lines are skipped. Every line
     *  is checked first (400 when one is not such a line, 404 when it names no person or period on
     *  the sheet); one change, so when any line is refused nothing is added. */
    @Transactional
    public Openings importOpenings(String sheetId, String user, String text) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        List<Period> sheetPeriods = periods.findBySheetIdOrderByFromAsc(sheetId);
        List<Opening> lines = new ArrayList<>();
        int n = 0;
        for (String raw : text.split("\\r?\\n|\\r")) {
            n++;
            if (raw.isBlank()) continue;
            String[] f = raw.split(",", -1);
            if (f.length != 3) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": give name,period,amount");
            }
            String name = f[0].trim();
            String periodName = f[1].trim();
            String amount = f[2].trim();
            if (name.isEmpty() || periodName.isEmpty() || !AMOUNT.matcher(amount).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Line " + n + ": give a name, a period and an amount of two decimals, 0.00 or more");
            }
            Person person = people.findBySheetIdAndNameOrAlias(sheetId, name)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No person " + name + " on the sheet"));
            Period period = sheetPeriods.stream().filter(p -> p.getName().equalsIgnoreCase(periodName)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No period " + periodName + " on the sheet"));
            lines.add(new Opening(person, period, new BigDecimal(amount)));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        List<Period> changed = new ArrayList<>();
        for (Opening line : lines) {
            line.period().addOpening(line.person().getId(), line.amount());
            if (!changed.contains(line.period())) {
                changed.add(line.period());
            }
            audit.recordOnCommit("OPENING_SET sheet=" + sheetId + " by=" + user + " name=" + line.person().getName()
                    + " period=" + line.period().getName() + " amount=" + line.amount().toPlainString());
        }
        periods.flush();
        changed.sort(Comparator.comparing(Period::getFrom));
        return new Openings(changed, lines.size());
    }

    /** Close a period, fixing each person's due-total and carried figure for it, and its own due-total (their sum), at the
     *  figures they show now (afresh for a soft-closed one); refused (403) when the user's role may
     *  not close by the sheet's permission matrix, and (409) when it is already closed. */
    @Transactional
    public Period close(String sheetId, String user, long id) {
        // held until the transaction ends, so no claim in the period changes while it is closed
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        settings.refuseUnlessAllowed(sheetId, user, Permission.CLOSE);
        if (period.isClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed");
        }
        closeNow(sheetId, user, period, "");
        return period;
    }

    /** Soft-close an open period, fixing its figures as a close does while claims due inside it
     *  can still be changed; refused (403) when the user's role may not close by the sheet's
     *  permission matrix, and (409) when it is already closed or soft-closed. */
    @Transactional
    public Period softClose(String sheetId, String user, long id) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        settings.refuseUnlessAllowed(sheetId, user, Permission.CLOSE);
        if (period.isClosed() || period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is already closed");
        }
        softCloseNow(sheetId, user, period);
        return period;
    }

    /** Soft-close the period, fixing its figures as they show now, and audit it. */
    private void softCloseNow(String sheetId, String user, Period period) {
        period.softClose(rollup.dueByPersonBetween(sheetId, period.getFrom(), period.getTo()), carriedInto(sheetId, period));
        fixCuts(sheetId, period);
        periods.flush();
        audit.recordOnCommit("PERIOD_SOFT_CLOSED sheet=" + sheetId + " by=" + user
                + " period=" + period.getName() + " total=" + period.getClosedTotal().toPlainString());
    }

    /** Once the sheet's today changes, close every open period whose to-date plus the sheet's
     *  grace days (when set) is before the new today, in order of their to-dates, each as a close
     *  by hand would; a soft-closed one keeps the figures it fixed. */
    @EventListener
    @Transactional
    public void closePastGrace(TodayChanged change) {
        Integer graceDays = sheets.find(change.sheetId()).map(Sheet::getGraceDays).orElse(null);
        if (graceDays == null) {
            return;
        }
        periods.findBySheetIdOrderByFromAsc(change.sheetId()).stream()
                .filter(p -> !p.isClosed() && !p.isSoftClosed() && p.getTo().plusDays(graceDays).isBefore(change.today()))
                .sorted(Comparator.comparing(Period::getTo))
                .forEach(p -> closeNow(change.sheetId(), change.user(), p, " auto=yes"));
    }

    /** Fix the period's figures as they show now and audit it, with any trailing fields. */
    private void closeNow(String sheetId, String user, Period period, String trailing) {
        period.close(rollup.dueByPersonBetween(sheetId, period.getFrom(), period.getTo()), carriedInto(sheetId, period));
        fixCuts(sheetId, period);
        periods.flush();
        BigDecimal total = period.getClosedTotal();
        audit.recordOnCommit("PERIOD_CLOSED sheet=" + sheetId + " by=" + user
                + " period=" + period.getName() + " total=" + total.toPlainString() + trailing);
    }

    /** Fix the period's pool members' cuts as the sheet stands now, taken on the sheet's today. */
    private void fixCuts(String sheetId, Period period) {
        period.fixCuts(history.lastVersion(sheetId), sheets.today(sheetId));
    }

    /** Each person's carried figure into the period as it shows now, by person id: what their
     *  claims carry into it plus their opening balance for it. */
    private Map<Long, BigDecimal> carriedInto(String sheetId, Period period) {
        List<Period> all = periods.findBySheetIdOrderByFromAsc(sheetId);
        Map<Long, BigDecimal> carried = rollup.carriedByPersonInto(sheetId, period.getFrom(), period.getTo(), date -> all.stream()
                .filter(p -> p.contains(date)).findFirst().map(Period::getTo).orElse(null));
        period.getOpenings().forEach((personId, amount) -> carried.computeIfPresent(personId, (id, c) -> c.add(amount)));
        return carried;
    }

    /** Reopen a closed or soft-closed period, its due-total, carried figures and cuts live again; refused (403) unless the
     *  user's role may reopen by the sheet's permission matrix (by default a steward), and (409)
     *  when it is neither closed nor soft-closed, or it is locked. */
    @Transactional
    public Period reopen(String sheetId, String user, long id) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        settings.refuseUnlessAllowed(sheetId, user, Permission.REOPEN);
        if (!period.isClosed() && !period.isSoftClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is not closed");
        }
        if (period.isLocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The period is locked");
        }
        period.reopen();
        periods.flush();
        audit.recordOnCommit("PERIOD_REOPENED sheet=" + sheetId + " by=" + user + " period=" + period.getName());
        return period;
    }

    /** Lock ({@code locked} true) or unlock a period: a locked period cannot be reopened; refused
     *  (403) unless the user is a steward, and (409) when it is already locked, or not locked. */
    @Transactional
    public Period lock(String sheetId, String user, long id, boolean locked) {
        sheets.lock(sheetId);
        Period period = periods.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such period"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may lock or unlock a period");
        }
        if (period.isLocked() == locked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    locked ? "The period is already locked" : "The period is not locked");
        }
        period.setLocked(locked);
        periods.flush();
        audit.recordOnCommit((locked ? "PERIOD_LOCKED" : "PERIOD_UNLOCKED") + " sheet=" + sheetId + " by=" + user
                + " period=" + period.getName());
        return period;
    }

    /** The sheet's period whose dates contain {@code date}, if any. */
    @Transactional(readOnly = true)
    public Optional<Period> containing(String sheetId, LocalDate date) {
        return periods.findBySheetIdOrderByFromAsc(sheetId).stream().filter(p -> p.contains(date)).findFirst();
    }

    /** Whether a soft-closed period of the sheet contains {@code date}. */
    @Transactional(readOnly = true)
    public boolean softClosedOn(String sheetId, LocalDate date) {
        return periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .anyMatch(p -> p.isSoftClosed() && p.contains(date));
    }

    /** Refuse (423) a change to a claim due by {@code date} when a closed period contains it. */
    @Transactional(readOnly = true)
    public void refuseIfClosed(String sheetId, LocalDate date) {
        periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> p.isClosed() && p.contains(date))
                .findFirst()
                .ifPresent(p -> {
                    throw new ResponseStatusException(HttpStatus.LOCKED,
                            "The claim's due-by is in closed period " + p.getName());
                });
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The period name is in use on the sheet");
    }
}
