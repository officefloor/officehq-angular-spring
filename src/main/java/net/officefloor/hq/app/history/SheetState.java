package net.officefloor.hq.app.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.patron.PatronController.PatronView;
import net.officefloor.hq.app.period.PeriodController.PeriodView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.rate.RateController.RateView;
import net.officefloor.hq.app.holiday.HolidayController.HolidayView;
import net.officefloor.hq.app.role.DelegationController.DelegationView;
import net.officefloor.hq.app.role.RoleController.RoleView;
import net.officefloor.hq.app.statement.StatementController.SummaryView;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.GradeFrom;
import net.officefloor.hq.app.person.Hold;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.sheet.Sheet;

/** What a sheet held at a moment. As far as a person's figures depend on it (its dated settings and policy date, its
 *  rates, its people and its claims) each part is rebuilt as an unsaved entity, so the figures of the sheet as it
 *  stood then are worked out by the same rules as the live ones. Alongside, the rest of what the screen shows of it,
 *  as the screen reads it: its other settings, and each list the screen reads (see {@link Views}). */
public record SheetState(List<SettingState> settings, LocalDate policyDate, List<RateState> rates,
        List<PersonState> people, List<ClaimState> claims, Map<String, String> permissions,
        LocalDate periodNumberingFrom, Integer graceDays, boolean archived, Views views, LocalDate entryDate,
        Integer lastSettleBatch) {

    /** Each list the screen reads of the sheet, as it read it then; a person's grade there is the one in force on the
     *  sheet's today then (see {@link #people(LocalDate)} for it on another day). */
    public record Views(List<PersonView> people, List<ClaimView> claims, List<RateView> rates,
            List<PeriodView> periods, List<SummaryView> statements, List<PatronView> patrons,
            Map<String, List<Long>> watches, List<RoleView> roles, List<DelegationView> delegations,
            List<HolidayView> holidays) {

        /** Nothing: a sheet as it stood before any change was made on it. */
        public static final Views NONE = new Views(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), List.of(), List.of(), List.of());
    }

    public record SettingState(String key, LocalDate from, String value) {
    }

    public record RateState(String grade, LocalDate from, LocalDate to, BigDecimal rate) {
    }

    public record GradeState(LocalDate from, String grade) {
    }

    public record HoldState(LocalDate from, LocalDate to) {
    }

    public record PersonState(long id, String name, List<GradeState> grades, Integer terms, LocalDate retiredFrom,
            List<HoldState> holds) {

        /** The person as they stood then, unsaved. */
        public Person rebuild(String sheetId) {
            Person person = new Person(sheetId, name, grades.get(0).grade());
            grades.stream().skip(1).forEach(g -> person.setGradeFrom(g.from(), g.grade()));
            person.setTerms(terms);
            person.setRetiredFrom(retiredFrom);
            holds.forEach(h -> person.addHold(new Hold(h.from(), h.to())));
            return person;
        }
    }

    public record ClaimState(long id, long personId, BigDecimal amount, LocalDate date, LocalDate dueBy, String note,
            String corrects, long listed, ClaimStatus status, String number, BigDecimal settledDue, boolean stated,
            BigDecimal statedDue, LocalDate deferredUntil, LocalDate remindedOn, String ref, String tags) {

        /** The claim as it stood then, unsaved. */
        public Claim rebuild(String sheetId) {
            Claim claim = new Claim(sheetId, personId, amount, date, dueBy, note, corrects, listed);
            claim.setStatus(status);
            claim.setNumber(number);
            claim.setSettledDue(settledDue);
            claim.setDeferredUntil(deferredUntil);
            claim.remind(remindedOn);
            claim.setRef(ref);
            claim.setTags(tags);
            if (stated) {
                claim.bind(statedDue);
            }
            return claim;
        }
    }

    /** What the sheet holds now. */
    public static SheetState of(Sheet sheet, List<Rate> rates, List<Person> people, List<Claim> claims, Views views) {
        return new SheetState(
                sheet.getSettings().stream().map(s -> new SettingState(s.getKey(), s.getFrom(), s.getValue())).toList(),
                sheet.getPolicyDate(),
                rates.stream().map(r -> new RateState(r.getGrade(), r.getFromDate(), r.getToDate(), r.getRate())).toList(),
                people.stream().map(SheetState::stateOf).toList(),
                claims.stream().map(c -> new ClaimState(c.getId(), c.getPersonId(), c.getAmount(), c.getDate(),
                        c.getDueBy(), c.getNote(), c.getCorrects(), c.getListed(), c.getStatus(), c.getNumber(),
                        c.getSettledDue(), c.isStated(), c.getStatedDue(), c.getDeferredUntil(), c.getRemindedOn(), c.getRef(), c.getTags())).toList(),
                new TreeMap<>(sheet.getPermissions()), sheet.getPeriodNumberingFrom(), sheet.getGraceDays(),
                sheet.isArchived(), views, sheet.getEntryDate(), sheet.getLastSettleBatch());
    }

    /** The people as the screen read them then, each with the grade in force on {@code today}. */
    public List<PersonView> people(String sheetId, LocalDate today) {
        return views.people().stream().map(v -> person(v.id())
                .map(p -> new PersonView(v.id(), v.name(), p.rebuild(sheetId).gradeOn(today), v.grades(), v.terms(),
                        v.version(), v.retired(), v.retiredFrom(), v.held(), v.holds(), v.contact()))
                .orElse(v)).toList();
    }

    private static PersonState stateOf(Person p) {
        List<GradeFrom> grades = p.getGrades();
        return new PersonState(p.getId(), p.getName(),
                grades.stream().map(g -> new GradeState(g.getFromDate(), g.getGrade())).toList(), p.getTerms(),
                p.getRetiredFrom(), p.getHolds().stream().map(h -> new HoldState(h.getFromDate(), h.getToDate())).toList());
    }

    /** The sheet as it stood then, unsaved, with {@code today} standing in for its today. */
    public Sheet rebuildSheet(Sheet live, LocalDate today) {
        Sheet sheet = new Sheet(live.getId(), live.getCreatedBy(), live.getCreatedAt(), today);
        settings.forEach(s -> sheet.record(s.key(), s.from(), s.value()));
        sheet.setPolicyDate(policyDate);
        if (permissions != null) {
            permissions.forEach(sheet::setPermission);
        }
        sheet.setPeriodNumberingFrom(periodNumberingFrom);
        sheet.setGraceDays(graceDays);
        sheet.setEntryDate(entryDate);
        if (lastSettleBatch != null) {
            sheet.setLastSettleBatch(lastSettleBatch);
        }
        if (archived) {
            sheet.archive();
        }
        return sheet;
    }

    /** The rates as they stood then, unsaved. */
    public List<Rate> rebuildRates(String sheetId) {
        return rates.stream().map(r -> {
            Rate rate = new Rate(sheetId, r.grade(), r.from(), r.rate());
            rate.setToDate(r.to());
            return rate;
        }).toList();
    }

    /** The person with this id as they stood then; empty when they were not on the sheet yet. */
    public Optional<PersonState> person(long id) {
        return people.stream().filter(p -> p.id() == id).findFirst();
    }
}
