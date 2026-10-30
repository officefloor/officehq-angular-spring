package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rate.RateTable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a candidate rate for a grade would do to the dues shown: the OPEN and PENDING claims of people on that
 * grade (the grade in force on the claim's date) whose due would differ if the rate applied to the grade on
 * every date, and the sum of the differences. A claim bound by a statement keeps its due. Nothing is saved.
 */
@Service
public class RatePreviewService {

    /** How many claims would show a different due, and the sum of new due minus current due. */
    public record Preview(int count, BigDecimal diff) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final RateService rates;

    public RatePreviewService(PersonRepository people, ClaimRepository claims, RateService rates) {
        this.people = people;
        this.claims = claims;
        this.rates = rates;
    }

    @Transactional(readOnly = true)
    public Preview preview(String sheetId, String grade, BigDecimal pct) {
        RateTable table = rates.table(sheetId);
        List<Rate> candidate = new ArrayList<>(table.rates().stream().filter(r -> !r.getGrade().equals(grade)).toList());
        candidate.add(new Rate(sheetId, grade, null, null, pct.setScale(2, RoundingMode.HALF_UP)));
        RateTable then = new RateTable(candidate, table.today(), table.settings(), table.policyDate(),
                table.ancestors());
        Map<Long, List<Claim>> byPerson = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .collect(Collectors.groupingBy(Claim::getPersonId));
        int count = 0;
        BigDecimal diff = BigDecimal.ZERO;
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            for (Claim claim : byPerson.getOrDefault(person.getId(), List.of())) {
                if (!claim.getStatus().unpaid() || Dues.bound(claim) || !grade.equals(person.gradeOn(claim.getDate()))) {
                    continue;
                }
                BigDecimal now = Dues.figure(claim, person::gradeOn, table);
                BigDecimal next = Dues.figure(claim, person::gradeOn, then);
                if (!Objects.equals(now == null ? null : now.setScale(2, RoundingMode.HALF_UP),
                        next == null ? null : next.setScale(2, RoundingMode.HALF_UP))) {
                    count++;
                    diff = diff.add(next == null ? BigDecimal.ZERO : next).subtract(now == null ? BigDecimal.ZERO : now);
                }
            }
        }
        return new Preview(count, diff.setScale(2, RoundingMode.HALF_UP));
    }
}
