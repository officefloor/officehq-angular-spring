package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import net.officefloor.hq.app.grade.GradeService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The rates for the grades on a sheet: listed and set. */
@Service
public class RateService {

    private final RateRepository rates;
    private final SheetService sheets;
    private final Rollups rollups;
    private final Audit audit;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final Dues dues;
    private final RateVersionRepository versions;

    private final GradeService grades;
    private final RoleService roles;

    public RateService(RateRepository rates, SheetService sheets, Rollups rollups, Audit audit, PersonRepository people,
            ClaimRepository claims, Dues dues, RateVersionRepository versions, GradeService grades, RoleService roles) {
        this.grades = grades;
        this.roles = roles;
        this.rates = rates;
        this.versions = versions;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
        this.people = people;
        this.claims = claims;
        this.dues = dues;
    }

    /**
     * What a candidate rate for a grade would change: how many OPEN and PENDING claims of people on
     * the grade (the grade in force on the claim's date) would show a different due, and the sum of
     * the differences (new due minus current due).
     */
    public record Preview(int count, BigDecimal diff) {
    }

    /**
     * Preview a candidate rate applied to a grade on every date, in place of the grade's rates;
     * nothing is saved and nothing is audited. A claim bound by a statement keeps its figure, and a
     * settled claim is not counted.
     */
    @Transactional(readOnly = true)
    public Preview preview(String sheetId, String grade, BigDecimal pct) {
        grades.requireGrade(sheetId, grade);
        Dues.Basis current = dues.live(sheetId);
        List<Rate> candidate = new ArrayList<>(current.rates().stream()
                .filter(rate -> !rate.getGrade().equals(grade)).toList());
        candidate.add(new Rate(sheetId, grade, null, pct.setScale(2, RoundingMode.UNNECESSARY)));
        Dues.Basis previewed = new Dues.Basis(current.sheet(), candidate, current.inherited(), current.corrected());
        Map<Long, Person> byId = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        int count = 0;
        BigDecimal diff = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            Person person = byId.get(claim.getPersonId());
            if (person == null || !claim.getStatus().isOpen() || claim.getSettledDue() != null
                    || !grade.equals(person.gradeOn(claim.getDate()))) {
                continue;
            }
            BigDecimal was = dues.due(claim, grade, current);
            BigDecimal now = dues.due(claim, grade, previewed);
            if (was == null ? now == null : now != null && was.compareTo(now) == 0) {
                continue;
            }
            count++;
            diff = diff.add(orZero(now)).subtract(orZero(was));
        }
        return new Preview(count, diff.setScale(2, RoundingMode.UNNECESSARY));
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** The sheet's rates by grade. */
    @Transactional(readOnly = true)
    public List<Rate> list(String sheetId) {
        return rates.findBySheetIdOrderByGradeAscFromDateAscIdAsc(sheetId);
    }

    /**
     * Set the rate for a grade from a date (null: from the beginning of time), applying through a
     * to-date (null: no end); a to-date before the from-date is refused. When the grade already has a
     * rate from that same date, its value is kept as an older version and the new one becomes its
     * newest version, the one in force. A rate saved with a from-date on or after the sheet's today is
     * forward-only: it applies only to claims added after it is saved, those already on the sheet keeping
     * the rates that applied to them; one with no from-date, or one before the sheet's today, applies to
     * every claim.
     */
    @Transactional
    public synchronized Rate set(String sheetId, String user, String grade, LocalDate from, LocalDate to,
            BigDecimal pct) {
        grades.requireGrade(sheetId, grade);
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rate's to-date cannot be before its from-date.");
        }
        LocalDate today = sheets.load(sheetId).getToday();
        Rollups.Rollup before = rollups.take(sheetId);
        BigDecimal percent = pct.setScale(2, RoundingMode.UNNECESSARY);
        Long forwardAfter = from != null && !from.isBefore(today) ? rates.nextAddedPlace() : null;
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from).orElse(null);
        if (rate == null) {
            rate = new Rate(sheetId, grade, from, percent);
            rate.setToDate(to);
            rate.setForwardAfter(forwardAfter);
        } else {
            versions.save(rate.replace(percent, to, forwardAfter));
        }
        Rate saved = rates.saveAndFlush(rate);
        audit.recordOnCommit("RATE_SET sheet=" + sheetId + " by=" + user
                + " grade=" + saved.getGrade() + " rate=" + saved.getPct().toPlainString()
                + (saved.getFromDate() != null ? " from=" + saved.getFromDate() : "")
                + (saved.getToDate() != null ? " to=" + saved.getToDate() : ""));
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Withdraw the newest version of a grade's rate from a date (null: from the beginning of time): the
     * version before it, when there is one, applies again; with none the rate is gone. Refused for
     * anyone but a steward, and when the grade has no rate from that date.
     */
    @Transactional
    public synchronized List<Rate> withdraw(String sheetId, String user, String grade, LocalDate from) {
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may withdraw a rate.");
        }
        Rate rate = rates.findBySheetIdAndGradeAndFromDate(sheetId, grade, from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The grade has no rate from that date."));
        Rollups.Rollup before = rollups.take(sheetId);
        rate.withdraw().ifPresentOrElse(restored -> {
            versions.delete(restored);
            rates.saveAndFlush(rate);
        }, () -> {
            rates.delete(rate);
            rates.flush();
        });
        audit.recordOnCommit("RATE_WITHDRAWN sheet=" + sheetId + " by=" + user + " grade=" + grade
                + (from != null ? " from=" + from : ""));
        rollups.audit(sheetId, user, before);
        return list(sheetId);
    }
}
