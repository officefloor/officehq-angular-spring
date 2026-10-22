package net.officefloor.hq.app.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.period.PeriodController.PeriodView;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateController.RateView;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.sheet.DatedSetting;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.TodayLeaving;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sheet as it stood at the end of past dates. Every change is made on the sheet's today, so as the
 * sheet's today moves on from a date, everything its figures are read from (its settings, rates,
 * people and claims) is kept as it stands: what was changed while the sheet's today was on or before
 * that date, and nothing changed later.
 */
@Service
public class SheetHistory {

    /** A sheet's figures-bearing state, as kept. */
    record State(LocalDate policyDate, List<SettingState> settings, List<RateState> rates,
            List<PersonState> people, List<ClaimState> claims) {
    }

    record SettingState(String key, LocalDate from, String value) {
    }

    record RateState(String grade, LocalDate from, LocalDate to, BigDecimal pct) {
    }

    record GradeState(LocalDate from, String grade) {
    }

    record HoldState(LocalDate from, LocalDate to) {
    }

    record PersonState(long id, String name, String grade, List<GradeState> grades, Integer terms,
            List<HoldState> holds, LocalDate retiredFrom, String contact, List<String> aliases) {
    }

    record ClaimState(long id, long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, ClaimStatus status,
            String number, BigDecimal settledDue, boolean stated, BigDecimal statedDue, String corrects,
            LocalDate deferredUntil) {
    }

    /**
     * The sheet as it stood at the end of a date, held in no store: the sheet with that date as its
     * today, its people by id and its claims by id, newest first, and the basis their figures are read
     * against.
     */
    public record AsOf(Sheet sheet, Map<Long, Person> people, Map<Long, Claim> claims, Dues.Basis basis) {
    }

    /**
     * What the screen is told about the sheet as it stood at the end of a date: its people, claims
     * (newest first), rates and periods, and whether it was archived.
     */
    public record Views(boolean archived, List<PersonView> people, List<ClaimView> claims, List<RateView> rates,
            List<PeriodView> periods) {

        /** The sheet before anything was kept for it. */
        static final Views EMPTY = new Views(false, List.of(), List.of(), List.of(), List.of());
    }

    private final SheetSnapshotRepository snapshots;
    private final SheetRepository sheets;
    private final RateRepository rates;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final PeriodRepository periods;
    private final JsonMapper json;

    public SheetHistory(SheetSnapshotRepository snapshots, SheetRepository sheets, RateRepository rates,
            PersonRepository people, ClaimRepository claims, PeriodRepository periods, JsonMapper json) {
        this.snapshots = snapshots;
        this.sheets = sheets;
        this.rates = rates;
        this.people = people;
        this.claims = claims;
        this.periods = periods;
        this.json = json;
    }

    /** The sheet's today moves on: keep the sheet as it stands, as it was at the end of the date it leaves. */
    @EventListener
    @Transactional
    public void todayLeaving(TodayLeaving leaving) {
        String sheetId = leaving.sheetId();
        Sheet sheet = sheets.findById(sheetId).orElseThrow();
        List<Rate> sheetRates = rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId);
        List<Person> sheetPeople = people.findBySheetIdOrderByIdAsc(sheetId);
        List<Claim> sheetClaims = claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
        State state = new State(sheet.getPolicyDate(),
                sheet.getDated().stream().map(d -> new SettingState(d.getKey(), d.getFromDate(), d.getValue())).toList(),
                sheetRates.stream().map(r -> new RateState(r.getGrade(), r.getFromDate(), r.getToDate(), r.getPct())).toList(),
                sheetPeople.stream().map(SheetHistory::kept).toList(),
                sheetClaims.stream().map(SheetHistory::kept).toList());
        Views views = new Views(sheet.isArchived(), sheetPeople.stream().map(PersonView::of).toList(),
                sheetClaims.stream().map(ClaimView::of).toList(), sheetRates.stream().map(RateView::of).toList(),
                periods.findBySheetIdOrderByFromDateAsc(sheetId).stream().map(PeriodView::of).toList());
        snapshots.save(new SheetSnapshot(sheetId, leaving.today(), json.writeValueAsString(state),
                json.writeValueAsString(views)));
    }

    /**
     * What a screen showing the sheet as of a date before its today is told: the views kept for the
     * last snapshot on or before that date, or an empty sheet when none was kept by then. Empty when
     * no as-of date is set (null), or it is on or after the sheet's today: every change made so far
     * counts then, and the screen is told the sheet as it stands.
     */
    @Transactional(readOnly = true)
    public Optional<Views> views(String sheetId, LocalDate asOf) {
        if (!past(sheetId, asOf)) {
            return Optional.empty();
        }
        return Optional.of(snapshots.findFirstBySheetIdAndAsOfLessThanEqualOrderByIdDesc(sheetId, asOf)
                .map(snapshot -> snapshot.getViews() == null ? Views.EMPTY
                        : json.readValue(snapshot.getViews(), Views.class))
                .orElse(Views.EMPTY));
    }

    /**
     * The sheet a screen showing it as of a date (null: none) is told about, held in no store: with no
     * as-of date the sheet as it stands; else the sheet with the as-of date as its today, its settings
     * as they stood at the end of that date (as they stand when it is on or after the sheet's today).
     */
    @Transactional(readOnly = true)
    public Sheet sheet(Sheet now, LocalDate asOf) {
        if (asOf == null) {
            return now;
        }
        if (!asOf.isBefore(now.getToday())) {
            return now.asOf(asOf, now.getDated(), now.getPolicyDate());
        }
        Sheet then = at(now.getId(), asOf).map(AsOf::sheet)
                .orElseGet(() -> now.asOf(asOf, List.of(), null));
        then.setArchived(views(now.getId(), asOf).orElse(Views.EMPTY).archived());
        return then;
    }

    /** Whether an as-of date (null: none) is before the sheet's today. */
    private boolean past(String sheetId, LocalDate asOf) {
        return asOf != null && sheets.findById(sheetId).map(s -> asOf.isBefore(s.getToday())).orElse(false);
    }

    /**
     * The sheet as it stood at the end of a date before its today; empty when nothing was kept for
     * it on or before that date.
     */
    @Transactional(readOnly = true)
    public Optional<AsOf> at(String sheetId, LocalDate day) {
        Sheet now = sheets.findById(sheetId).orElse(null);
        if (now == null) {
            return Optional.empty();
        }
        return snapshots.findFirstBySheetIdAndAsOfLessThanEqualOrderByIdDesc(sheetId, day)
                .map(snapshot -> restored(now, day, json.readValue(snapshot.getState(), State.class)));
    }

    private static AsOf restored(Sheet now, LocalDate day, State state) {
        Sheet sheet = now.asOf(day,
                state.settings().stream().map(s -> new DatedSetting(s.key(), s.from(), s.value())).toList(),
                state.policyDate());
        List<Rate> rates = state.rates().stream().map(r -> {
            Rate rate = new Rate(now.getId(), r.grade(), r.from(), r.pct());
            rate.setToDate(r.to());
            return rate;
        }).toList();
        Map<Long, Person> people = new LinkedHashMap<>();
        for (PersonState p : state.people()) {
            Person person = new Person(now.getId(), p.name(), p.grade(), p.terms(), p.contact());
            p.grades().forEach(g -> person.gradeFrom(g.grade(), g.from()));
            p.holds().forEach(h -> person.hold(h.from(), h.to()));
            if (p.aliases() != null) {
                p.aliases().forEach(person::addAlias);
            }
            if (p.retiredFrom() != null) {
                person.retire(p.retiredFrom());
            }
            people.put(p.id(), person);
        }
        Map<Long, Claim> claims = new LinkedHashMap<>();
        for (ClaimState c : state.claims()) {
            Claim claim = new Claim(now.getId(), c.personId(), c.amount(), c.date(), 0, c.dueBy(), "", c.corrects());
            claim.become(c.status());
            claim.number(c.number());
            claim.settleDue(c.settledDue());
            if (c.stated()) {
                claim.bind(c.statedDue());
            }
            claim.defer(c.deferredUntil());
            claims.put(c.id(), claim);
        }
        Dues.Basis basis = new Dues.Basis(sheet, rates, correction -> claims.values().stream()
                .filter(c -> Objects.equals(c.getPersonId(), correction.getPersonId())
                        && Objects.equals(c.getNumber(), correction.getCorrects()))
                .findFirst());
        return new AsOf(sheet, people, claims, basis);
    }

    private static PersonState kept(Person p) {
        return new PersonState(p.getId(), p.getName(), p.getGrade(),
                p.getGradesFrom().entrySet().stream().map(e -> new GradeState(e.getKey(), e.getValue())).toList(),
                p.getTerms(), p.getHolds().stream().map(h -> new HoldState(h.getFromDate(), h.getToDate())).toList(),
                p.getRetiredFrom(), p.getContact(), p.getAliases());
    }

    private static ClaimState kept(Claim c) {
        return new ClaimState(c.getId(), c.getPersonId(), c.getAmount(), c.getDate(), c.getDueBy(), c.getStatus(),
                c.getNumber(), c.getSettledDue(), c.isStated(), c.getStatedDue(), c.getCorrects(), c.getDeferredUntil());
    }
}
