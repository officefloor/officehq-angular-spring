package net.officefloor.hq.app.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.GradeFrom;
import net.officefloor.hq.app.person.Hold;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.sheet.Sheet;

/** What a sheet held at a moment, as far as a person's figures depend on it: its dated settings and policy date,
 *  its rates, its people and its claims. Each part is rebuilt as an unsaved entity, so the figures of the sheet as
 *  it stood then are worked out by the same rules as the live ones. */
public record SheetState(List<SettingState> settings, LocalDate policyDate, List<RateState> rates,
        List<PersonState> people, List<ClaimState> claims) {

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
            BigDecimal statedDue, LocalDate deferredUntil) {

        /** The claim as it stood then, unsaved. */
        public Claim rebuild(String sheetId) {
            Claim claim = new Claim(sheetId, personId, amount, date, dueBy, note, corrects, listed);
            claim.setStatus(status);
            claim.setNumber(number);
            claim.setSettledDue(settledDue);
            claim.setDeferredUntil(deferredUntil);
            if (stated) {
                claim.bind(statedDue);
            }
            return claim;
        }
    }

    /** What the sheet holds now. */
    public static SheetState of(Sheet sheet, List<Rate> rates, List<Person> people, List<Claim> claims) {
        return new SheetState(
                sheet.getSettings().stream().map(s -> new SettingState(s.getKey(), s.getFrom(), s.getValue())).toList(),
                sheet.getPolicyDate(),
                rates.stream().map(r -> new RateState(r.getGrade(), r.getFromDate(), r.getToDate(), r.getRate())).toList(),
                people.stream().map(SheetState::stateOf).toList(),
                claims.stream().map(c -> new ClaimState(c.getId(), c.getPersonId(), c.getAmount(), c.getDate(),
                        c.getDueBy(), c.getNote(), c.getCorrects(), c.getListed(), c.getStatus(), c.getNumber(),
                        c.getSettledDue(), c.isStated(), c.getStatedDue(), c.getDeferredUntil())).toList());
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
