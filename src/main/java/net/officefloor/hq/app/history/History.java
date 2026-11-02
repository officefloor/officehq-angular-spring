package net.officefloor.hq.app.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimRevision;
import net.officefloor.hq.app.claim.ClaimRevisionRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.period.PeriodRevision;
import net.officefloor.hq.app.period.PeriodRevisionRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRevision;
import net.officefloor.hq.app.person.PersonRevisionRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rate.RateRevision;
import net.officefloor.hq.app.rate.RateRevisionRepository;
import net.officefloor.hq.app.sheet.SettingRevision;
import net.officefloor.hq.app.sheet.SettingRevisionRepository;
import net.officefloor.hq.app.sheet.SettingValue;
import net.officefloor.hq.app.sheet.SettingValueRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetRevision;
import net.officefloor.hq.app.sheet.SheetRevisionRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A sheet's history: after each change that can move its figures, a revision of every claim,
 * person, rate and dated setting the change left different from its last revision (and of the
 * policy date), stamped with the sheet's today then. The sheet as it stood at the end of a day is
 * the last revision of each stamped on or before that day: every change made while the sheet's
 * today was on or before it counts, every later one does not.
 */
@Service
public class History {

    /** A sheet as it stood at the end of a day: its people, claims, rates (by grade and then
     *  from-date, the one with no from-date first), dated setting values (latest from-date first),
     *  policy date (null when blank) and periods (earliest first) then, all detached and never saved;
     *  and the basis its figures are worked out on, the day standing in for its today. */
    public record SheetAsOf(LocalDate day, Dues.Basis basis, Map<Long, Person> people, List<Claim> claims,
            List<Rate> rates, List<SettingValue> settings, LocalDate policyDate, List<Period> periods) {

        /** The person as they stood then; empty when they were not on the sheet yet. */
        public Optional<Person> person(long personId) {
            return Optional.ofNullable(people.get(personId));
        }

        /** The claims the person had then. */
        public List<Claim> claimsOf(long personId) {
            return claims.stream().filter(c -> c.getPersonId() == personId).toList();
        }

        /** The value of a setting in force on a day then; empty when none was. */
        public Optional<SettingValue> setting(String key, LocalDate on) {
            return inForce(settings, key, on);
        }

        /** The values recorded then for a setting, earliest from-date first. */
        public List<SettingValue> recorded(String key) {
            return settings.stream().filter(v -> v.getKey().equals(key))
                    .sorted(Comparator.comparing(SettingValue::getFrom)).toList();
        }
    }

    private final ClaimRepository claims;
    private final ClaimRevisionRepository claimRevisions;
    private final PersonRepository people;
    private final PersonRevisionRepository personRevisions;
    private final RateRepository rates;
    private final RateRevisionRepository rateRevisions;
    private final SettingValueRepository settingValues;
    private final SettingRevisionRepository settingRevisions;
    private final SheetRepository sheets;
    private final SheetRevisionRepository sheetRevisions;
    private final PeriodRepository periods;
    private final PeriodRevisionRepository periodRevisions;

    public History(ClaimRepository claims, ClaimRevisionRepository claimRevisions, PersonRepository people,
            PersonRevisionRepository personRevisions, RateRepository rates, RateRevisionRepository rateRevisions,
            SettingValueRepository settingValues, SettingRevisionRepository settingRevisions, SheetRepository sheets,
            SheetRevisionRepository sheetRevisions, PeriodRepository periods,
            PeriodRevisionRepository periodRevisions) {
        this.claims = claims;
        this.claimRevisions = claimRevisions;
        this.people = people;
        this.personRevisions = personRevisions;
        this.rates = rates;
        this.rateRevisions = rateRevisions;
        this.settingValues = settingValues;
        this.settingRevisions = settingRevisions;
        this.sheets = sheets;
        this.sheetRevisions = sheetRevisions;
        this.periods = periods;
        this.periodRevisions = periodRevisions;
    }

    /** Record, stamped with the sheet's today, a revision of everything on the sheet that a change
     *  just left different from its last revision. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String sheetId) {
        sheets.findById(sheetId).ifPresent(sheet -> {
            LocalDate today = sheet.getToday();
            Map<Long, ClaimRevision> claimsWere = last(claimRevisions.findBySheetIdOrderByIdAsc(sheetId),
                    ClaimRevision::getClaimId);
            for (Claim claim : claims.findBySheetIdOrderByListedDesc(sheetId)) {
                ClaimRevision was = claimsWere.get(claim.getId());
                if (was == null || !was.matches(claim)) {
                    claimRevisions.save(new ClaimRevision(claim, today));
                }
            }
            Map<Long, PersonRevision> peopleWere = last(personRevisions.findBySheetIdOrderByIdAsc(sheetId),
                    PersonRevision::getPersonId);
            for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
                PersonRevision was = peopleWere.get(person.getId());
                if (was == null || !was.matches(person)) {
                    personRevisions.save(new PersonRevision(person, today));
                }
            }
            Map<String, RateRevision> ratesWere = last(rateRevisions.findBySheetIdOrderByIdAsc(sheetId),
                    RateRevision::key);
            for (Rate rate : rates.findBySheetIdOrdered(sheetId)) {
                RateRevision was = ratesWere.get(RateRevision.key(rate));
                if (was == null || !was.matches(rate)) {
                    rateRevisions.save(new RateRevision(rate, today));
                }
            }
            Map<String, SettingRevision> settingsWere = last(settingRevisions.findBySheetIdOrderByIdAsc(sheetId),
                    SettingRevision::key);
            for (SettingValue value : settingValues.findBySheetId(sheetId)) {
                SettingRevision was = settingsWere.get(SettingRevision.key(value));
                if (was == null || !was.matches(value)) {
                    settingRevisions.save(new SettingRevision(value, today));
                }
            }
            Map<Long, PeriodRevision> periodsWere = last(periodRevisions.findBySheetIdOrderByIdAsc(sheetId),
                    PeriodRevision::getPeriodId);
            for (Period period : periods.findBySheetIdOrderByFromDateAsc(sheetId)) {
                PeriodRevision was = periodsWere.get(period.getId());
                if (was == null || !was.matches(period)) {
                    periodRevisions.save(new PeriodRevision(period, today));
                }
            }
            List<SheetRevision> sheetWas = sheetRevisions.findBySheetIdOrderByIdAsc(sheetId);
            LocalDate policyWas = sheetWas.isEmpty() ? null : sheetWas.get(sheetWas.size() - 1).getPolicyDate();
            if (!Objects.equals(policyWas, sheet.getPolicyDate())) {
                sheetRevisions.save(new SheetRevision(sheetId, today, sheet.getPolicyDate()));
            }
        });
    }

    /** The sheet as it stood at the end of a day. */
    @Transactional(readOnly = true)
    public SheetAsOf asOf(String sheetId, LocalDate day) {
        Map<Long, Person> then = new LinkedHashMap<>();
        lastOn(personRevisions.findBySheetIdOrderByIdAsc(sheetId), PersonRevision::getRecordedOn,
                PersonRevision::getPersonId, day).forEach((id, revision) -> then.put(id, revision.toPerson()));
        List<Claim> claimsThen = lastOn(claimRevisions.findBySheetIdOrderByIdAsc(sheetId),
                ClaimRevision::getRecordedOn, ClaimRevision::getClaimId, day).values().stream()
                .map(ClaimRevision::toClaim).toList();
        List<Rate> ratesThen = lastOn(rateRevisions.findBySheetIdOrderByIdAsc(sheetId), RateRevision::getRecordedOn,
                RateRevision::key, day).values().stream().map(RateRevision::toRate)
                .sorted(Comparator.comparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
                        .reversed())
                .toList();
        List<SettingValue> settingsThen = lastOn(settingRevisions.findBySheetIdOrderByIdAsc(sheetId),
                SettingRevision::getRecordedOn, SettingRevision::key, day).values().stream()
                .map(SettingRevision::toValue)
                .sorted(Comparator.comparing(SettingValue::getFrom).reversed()).toList();
        LocalDate policyDate = sheetRevisions.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(r -> !r.getRecordedOn().isAfter(day)).reduce((a, b) -> b)
                .map(SheetRevision::getPolicyDate).orElse(null);
        List<Period> periodsThen = lastOn(periodRevisions.findBySheetIdOrderByIdAsc(sheetId),
                PeriodRevision::getRecordedOn, PeriodRevision::getPeriodId, day).values().stream()
                .map(PeriodRevision::toPeriod).sorted(Comparator.comparing(Period::getFromDate)).toList();
        List<Rate> ratesListed = ratesThen.stream().sorted(Comparator.comparing(Rate::getGrade)
                .thenComparing(Rate::getFromDate, Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder())))
                .toList();
        Dues.Basis basis = new Dues.Basis() {
            @Override
            public LocalDate today() {
                return day;
            }

            @Override
            public BigDecimal floor(LocalDate on) {
                return inForce(settingsThen, "floor", on).map(SettingValue::getValue)
                        .orElse(SheetService.STARTING_FLOOR);
            }

            @Override
            public BigDecimal latePct(LocalDate on) {
                return inForce(settingsThen, "late-pct", on).map(SettingValue::getValue)
                        .orElse(SheetService.STARTING_LATE_PCT);
            }

            @Override
            public BigDecimal cap() {
                return inForce(settingsThen, "cap", day).map(SettingValue::getValue).orElse(null);
            }

            @Override
            public LocalDate policyDateInForce() {
                return policyDate != null ? policyDate : day;
            }

            @Override
            public List<Rate> rates(String grade) {
                return ratesThen.stream().filter(r -> r.getGrade().equals(grade)).toList();
            }

            @Override
            public int grace(String grade, LocalDate on) {
                return inForce(settingsThen, "grace-" + grade, on).map(SettingValue::getValue)
                        .map(BigDecimal::intValue).orElse(0);
            }
        };
        return new SheetAsOf(day, basis, then, claimsThen, ratesListed, settingsThen, policyDate, periodsThen);
    }

    /** The value of a setting in force on a day among values given latest from-date first. */
    private static Optional<SettingValue> inForce(List<SettingValue> latestFirst, String key, LocalDate day) {
        return latestFirst.stream().filter(v -> v.getKey().equals(key) && !v.getFrom().isAfter(day)).findFirst();
    }

    /** The last of each thing's revisions, given in the order they were recorded. */
    private static <K, R> Map<K, R> last(List<R> revisions, Function<R, K> of) {
        Map<K, R> last = new LinkedHashMap<>();
        revisions.forEach(r -> last.put(of.apply(r), r));
        return last;
    }

    /** The last of each thing's revisions recorded on or before a day, given in the order they were
     *  recorded. */
    private static <K, R> Map<K, R> lastOn(List<R> revisions, Function<R, LocalDate> recordedOn, Function<R, K> of,
            LocalDate day) {
        return last(revisions.stream().filter(r -> !recordedOn.apply(r).isAfter(day)).toList(), of);
    }
}
