package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.PolicyVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The due-totals of a sheet's policy versions: for each, the due figures (see Dues#figure) of the
 *  claims that count among those whose policy version it is (the one in force on the claim's date),
 *  added up. */
@Service
public class PolicyDues {

    private final ClaimRepository claims;
    private final PersonRepository persons;
    private final Dues dues;

    public PolicyDues(ClaimRepository claims, PersonRepository persons, Dues dues) {
        this.claims = claims;
        this.persons = persons;
        this.dues = dues;
    }

    /** The due-total of each of the versions given, by version number (0.00 when none of its claims
     *  counts or has a figure). */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> dueTotals(String sheetId, List<PolicyVersion> versions) {
        Dues.Basis basis = dues.now(sheetId);
        Map<Long, Person> people = persons.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        Map<Integer, BigDecimal> totals = new TreeMap<>();
        versions.forEach(v -> totals.put(v.getNumber(), BigDecimal.ZERO.setScale(2)));
        for (Claim c : claims.findBySheetIdOrderByListedDesc(sheetId)) {
            Person person = people.get(c.getPersonId());
            if (person == null || !c.getStatus().counts()) {
                continue;
            }
            BigDecimal figure = dues.figure(basis, person, c);
            if (figure != null) {
                totals.computeIfPresent(PolicyVersion.on(versions, c.getDate()), (n, t) -> t.add(figure));
            }
        }
        return totals;
    }
}
