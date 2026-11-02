package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates for each grade on a sheet, each applying from its from-date. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetService sheets;
    private final Rollup rollup;
    private final Audit audit;
    private final Dues dues;
    private final PersonRepository people;
    private final ClaimRepository claims;

    public RateService(RateRepository rates, SheetService sheets, Rollup rollup, Audit audit, Dues dues,
            PersonRepository people, ClaimRepository claims) {
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
        this.dues = dues;
        this.people = people;
        this.claims = claims;
    }

    /** What a candidate rate would change: how many claims would show a different due, and the sum of
     *  the differences (new due minus current due; a due of n/a taken as 0.00). */
    public record Preview(int count, BigDecimal diff) {
    }

    /** Preview a candidate rate applying to a grade on every date: the open and pending claims of
     *  people on that grade (the grade in force on the claim's date) whose due would differ. A claim
     *  bound by a statement keeps its bound figure; a settled claim is not counted. Saves nothing. */
    @Transactional(readOnly = true)
    public Preview preview(String sheetId, String grade, BigDecimal pct) {
        Dues.Basis now = dues.now(sheetId);
        List<Rate> candidate = List.of(new Rate(sheetId, grade, pct, null));
        Dues.Basis with = new Dues.Basis() {
            @Override
            public LocalDate today() {
                return now.today();
            }

            @Override
            public BigDecimal floor(LocalDate day) {
                return now.floor(day);
            }

            @Override
            public BigDecimal latePct(LocalDate day) {
                return now.latePct(day);
            }

            @Override
            public BigDecimal cap() {
                return now.cap();
            }

            @Override
            public LocalDate policyDateInForce() {
                return now.policyDateInForce();
            }

            @Override
            public List<Rate> rates(String g) {
                return g.equals(grade) ? candidate : now.rates(g);
            }

            @Override
            public int grace(String g, LocalDate day) {
                return now.grace(g, day);
            }
        };
        Map<Long, Person> byId = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        int count = 0;
        BigDecimal diff = zero;
        for (Claim claim : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING)
                    || !grade.equals(person.gradeOn(claim.getDate()))) {
                continue;
            }
            BigDecimal was = dues.figure(now, person, claim);
            BigDecimal would = dues.figure(with, person, claim);
            BigDecimal wasValue = was == null ? zero : was;
            BigDecimal wouldValue = would == null ? zero : would;
            if ((was == null) != (would == null) || wasValue.compareTo(wouldValue) != 0) {
                count++;
                diff = diff.add(wouldValue.subtract(wasValue));
            }
        }
        return new Preview(count, diff.setScale(2));
    }

    /** The rates set on the sheet, by grade and then from-date. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrdered(sheetId);
    }

    /** Set the rate for a grade from a date (null: from the beginning of time) through a date
     *  (null: no end), replacing the one the grade had for that from-date. */
    @Transactional
    public synchronized Rate set(String sheetId, String user, String grade, BigDecimal pct, LocalDate from,
            LocalDate to) {
        sheets.load(sheetId, user);
        return rollup.tracking(sheetId, user, () -> {
            Rate rate = (from == null
                    ? rates.findBySheetIdAndGradeAndFromDateIsNull(sheetId, grade)
                    : rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from))
                    .orElseGet(() -> new Rate(sheetId, grade, pct, from));
            rate.setPct(pct);
            rate.setToDate(to);
            Rate saved = rates.saveAndFlush(rate);
            audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user + " grade=" + grade
                    + " rate=" + saved.getPct().setScale(2).toPlainString()
                    + (from != null ? " from=" + from : "")
                    + (to != null ? " to=" + to : ""));
            return saved;
        });
    }
}
