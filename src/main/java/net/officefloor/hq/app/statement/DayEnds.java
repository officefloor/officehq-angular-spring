package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.ParentRates;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rate.RateTable;
import net.officefloor.hq.app.sheet.DatedSettings;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetSetting;
import net.officefloor.hq.app.sheet.SheetSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sheet as it stood at the end of each day it had as its today: when its today moves on, everything
 * its figures are made from (its claims, people, rates, dated settings and policy date) is written down
 * as it is then. The sheet at the end of a date gone by is the last one written for a day on or before
 * it: every change made while its today was on or before that date, and none made later.
 */
@Service
public class DayEnds {

    record ClaimRow(long id, long personId, BigDecimal amount, LocalDate date, ClaimStatus status,
            LocalDate correctsDate, LocalDate dueBy, LocalDate deferredUntil, BigDecimal settledDue, boolean stated,
            BigDecimal statedDue) {
    }

    record GradeRow(String grade, LocalDate from) {
    }

    record HoldRow(LocalDate from, LocalDate to) {
    }

    record PersonRow(long id, String name, List<GradeRow> grades, List<HoldRow> holds, LocalDate retiredFrom) {
    }

    record RateRow(String grade, LocalDate from, LocalDate to, BigDecimal pct) {
    }

    record SettingRow(String key, LocalDate from, String value) {
    }

    record State(LocalDate policyDate, List<RateRow> rates, List<SettingRow> settings, List<PersonRow> people,
            List<ClaimRow> claims) {
    }

    /**
     * The sheet as it stood at the end of a date: its rates and settings with that date as their today,
     * its people by id and each person's claims by id (newest first), none of them kept in the store.
     */
    public record SheetThen(RateTable table, Map<Long, Person> people, Map<Long, Map<Long, Claim>> claims) {

        /** The person's claims then, by id; none when they had none. */
        public Map<Long, Claim> claimsOf(long personId) {
            return claims.getOrDefault(personId, Map.of());
        }
    }

    private final DayEndRepository dayEnds;
    private final SheetRepository sheets;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final SheetSettingRepository settings;
    private final ParentRates parents;
    private final JsonMapper json;

    public DayEnds(DayEndRepository dayEnds, SheetRepository sheets, ClaimRepository claims,
            PersonRepository people, RateRepository rates, SheetSettingRepository settings, ParentRates parents,
            JsonMapper json) {
        this.parents = parents;
        this.dayEnds = dayEnds;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.settings = settings;
        this.json = json;
    }

    /** Write the sheet down as it stands now, as at the end of the day given (the today it is leaving). */
    @Transactional
    public void record(String sheetId, LocalDate day) {
        Sheet sheet = sheets.findById(sheetId).orElse(null);
        State state = new State(sheet == null ? null : sheet.getPolicyDate(),
                rates.findBySheetId(sheetId).stream()
                        .map(r -> new RateRow(r.getGrade(), r.getFromDate(), r.getToDate(), r.getPct())).toList(),
                settings.findBySheetId(sheetId).stream()
                        .map(s -> new SettingRow(s.getKey(), s.getFromDate(), s.getValue())).toList(),
                people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                        .map(p -> new PersonRow(p.getId(), p.getName(),
                                p.getGrades().stream().map(g -> new GradeRow(g.getGrade(), g.getFromDate())).toList(),
                                p.getHolds().stream().map(h -> new HoldRow(h.getFromDate(), h.getToDate())).toList(),
                                p.getRetiredFrom()))
                        .toList(),
                claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                        .map(c -> new ClaimRow(c.getId(), c.getPersonId(), c.getAmount(), c.getDate(), c.getStatus(),
                                c.getCorrectsDate(), c.getDueBy(), c.getDeferredUntil(), c.getSettledDue(),
                                c.isStated(), c.getStatedDue()))
                        .toList());
        dayEnds.saveAndFlush(new DayEnd(sheetId, day, json.writeValueAsString(state)));
    }

    /** The sheet as it stood at the end of the date, with that date as its today; empty when none was written by then. */
    @Transactional(readOnly = true)
    public Optional<SheetThen> asOf(String sheetId, LocalDate date) {
        return dayEnds.findFirstBySheetIdAndEndedOnLessThanEqualOrderByIdDesc(sheetId, date)
                .map(d -> then(sheetId, date, json.readValue(d.getState(), State.class)));
    }

    /** The sheet then; a grade with no rate in force then takes its parent sheets' rates as they are now. */
    private SheetThen then(String sheetId, LocalDate date, State state) {
        DatedSettings settingsThen = new DatedSettings(state.settings().stream()
                .map(s -> new SheetSetting(sheetId, s.key(), s.from(), s.value())).toList());
        RateTable table = new RateTable(
                state.rates().stream().map(r -> new Rate(sheetId, r.grade(), r.from(), r.to(), r.pct())).toList(),
                date, settingsThen, state.policyDate(), parents.from(sheetId, settingsThen.parent()));
        Map<Long, Person> byId = new LinkedHashMap<>();
        for (PersonRow row : state.people()) {
            byId.put(row.id(), person(sheetId, row));
        }
        Map<Long, Map<Long, Claim>> claimsByPerson = new LinkedHashMap<>();
        for (ClaimRow row : state.claims()) {
            claimsByPerson.computeIfAbsent(row.personId(), k -> new LinkedHashMap<>()).put(row.id(), claim(sheetId, row));
        }
        return new SheetThen(table, byId, claimsByPerson);
    }

    private static Person person(String sheetId, PersonRow row) {
        GradeRow first = row.grades().stream().filter(g -> g.from() == null).findFirst()
                .orElse(row.grades().isEmpty() ? new GradeRow(null, null) : row.grades().get(0));
        Person person = new Person(sheetId, row.name(), first.grade());
        row.grades().stream().filter(g -> g != first).forEach(g -> person.gradeFrom(g.grade(), g.from()));
        row.holds().forEach(h -> person.hold(h.from(), h.to()));
        person.setRetiredFrom(row.retiredFrom());
        return person;
    }

    private static Claim claim(String sheetId, ClaimRow row) {
        Claim claim = new Claim(sheetId, row.personId(), row.amount(), row.date(), "", null);
        claim.setStatus(row.status());
        claim.setCorrectsDate(row.correctsDate());
        claim.setDueBy(row.dueBy());
        claim.setDeferredUntil(row.deferredUntil());
        claim.setSettledDue(row.settledDue());
        if (row.stated()) {
            claim.bind(row.statedDue());
        }
        return claim;
    }
}
