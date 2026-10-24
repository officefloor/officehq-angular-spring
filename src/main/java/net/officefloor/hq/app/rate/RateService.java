package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates each grade has on each sheet, each from its from-date on (or from the beginning of time)
 * through its to-date (or with no end).
 * Setting a grade's rate for a from-date it already has a rate for replaces that rate. */
@Service
public class RateService {

    private final RateRepository rates;
    private final RateChain chain;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final ClaimRepository claims;
    private final PersonRepository people;

    public RateService(RateRepository rates, RateChain chain, SheetRepository sheets, Audit audit, RollupService rollup,
            ClaimRepository claims, PersonRepository people) {
        this.chain = chain;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** The sheet's rates, by grade, then by from-date. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAndFrom(sheetId);
    }

    /** The sheet's rates, then its parent's, and so on up to {@link RateChain#LEVELS_UP} levels up: each
     * by grade, then by from-date. */
    @Transactional(readOnly = true)
    public List<List<Rate>> levels(String sheetId) {
        return chain.levels(sheetId);
    }

    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new RateRefusedException("A rate's to-date cannot be before its from-date.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Rate rate = (from == null ? rates.findUndated(sheetId, grade)
                : rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from)).orElse(null);
        if (rate == null) {
            rate = rates.saveAndFlush(new Rate(sheetId, grade, from, to, pct));
        } else {
            rate.setPct(pct);
            rate.setTo(to);
            rates.flush();
        }
        List<String> fields = new ArrayList<>(List.of("grade=" + grade,
                "rate=" + rate.getPct().setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        if (from != null) fields.add("from=" + from);
        if (to != null) fields.add("to=" + to);
        audit.change("RATE_SET", sheetId, user, fields.toArray(String[]::new));
        rollup.recordSince(sheetId, user, before);
        return rate;
    }

    /** How many OPEN and PENDING claims would show a different due, and by how much in all (new minus
     * current), were {@code pct} the grade's rate on every date. */
    public record Preview(int count, BigDecimal diff) {
    }

    /** A preview of the grade's rate at {@code pct} on every date over the OPEN and PENDING claims of the
     * people on the grade on each claim's date; a claim a statement binds does not change. Saves nothing. */
    @Transactional(readOnly = true)
    public Preview preview(String sheetId, String grade, BigDecimal pct) {
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        RollupService.Basis live = rollup.live(sheetId);
        RollupService.Basis previewed = RollupService.withRate(live, grade, pct);
        int count = 0;
        BigDecimal diff = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByListedDescIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !claim.getStatus().isOpen() || !grade.equals(person.gradeOn(claim.getDate()))) continue;
            BigDecimal now = rollup.shownOf(claim, person, live);
            BigDecimal then = rollup.shownOf(claim, person, previewed);
            if (Objects.equals(now, then) || (now != null && then != null && now.compareTo(then) == 0)) continue;
            count++;
            diff = diff.add(then == null ? BigDecimal.ZERO : then).subtract(now == null ? BigDecimal.ZERO : now);
        }
        return new Preview(count, diff.setScale(2, RoundingMode.HALF_EVEN));
    }
}
