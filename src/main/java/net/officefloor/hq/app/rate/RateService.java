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
import net.officefloor.hq.app.grade.GradeService;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rates each grade has on each sheet, each from its from-date on (or from the beginning of time)
 * through its to-date (or with no end).
 * Setting a grade's rate for a from-date it already has a rate for adds a new version of that rate, which is
 * the one in force, and keeps the value it replaces as an older version. */
@Service
public class RateService {

    private final RateRepository rates;
    private final RateChain chain;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateVersionRepository versions;
    private final GradeService grades;

    public RateService(RateRepository rates, RateVersionRepository versions, RateChain chain, SheetRepository sheets, Audit audit, RollupService rollup,
            ClaimRepository claims, PersonRepository people, GradeService grades) {
        this.grades = grades;
        this.versions = versions;
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

    /** Sets the grade's rate from {@code from} through {@code to}. Saved with a from-date on or after the
     * sheet's today it is forward-only: it applies only to the claims added after it is saved, the claims
     * already added keeping the rates that applied to them (see {@link Rate#forClaim}). */
    @Transactional
    public Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        grades.requireGrade(sheetId, grade);
        if (from != null && to != null && to.isBefore(from)) {
            throw new RateRefusedException("A rate's to-date cannot be before its from-date.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        boolean forwardOnly = from != null && today != null && !from.isBefore(today);
        Rate.Term term = new Rate.Term(pct, to, forwardOnly ? claims.highestId() : null);
        Rate rate = (from == null ? rates.findUndated(sheetId, grade)
                : rates.findBySheetIdAndGradeAndFrom(sheetId, grade, from)).orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, from, to, pct);
            rate.setTerms(List.of(term));
            rate = rates.saveAndFlush(rate);
        } else {
            versions.save(new RateVersion(rate.getId(), rate.getVersion(), rate.getPct(), rate.getTo()));
            rate.setVersion(rate.getVersion() + 1);
            List<Rate.Term> terms = new ArrayList<>();
            terms.add(term);
            // A forward-only version leaves the claims already added on the terms that applied to them.
            if (forwardOnly) terms.addAll(rate.getTerms());
            rate.setTerms(terms);
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
        grades.requireGrade(sheetId, grade);
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
