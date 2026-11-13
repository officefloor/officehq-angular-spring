package net.officefloor.hq.app.quickadd;

import java.math.BigDecimal;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds a DRAFT claim for a person named on the sheet, adding the person first (grade A) when the
 *  name is not on it. The whole quick-add is one change: a refused claim leaves no person behind. */
@Service
public class QuickAddService {

    /** The grade a person added by a quick-add takes. */
    private static final String GRADE = "A";

    /** A quick-add's result: the person the claim is for and the claim added. */
    public record Added(Person person, Claim claim) {
    }

    private final PersonRepository people;
    private final PersonService personService;
    private final ClaimService claims;
    private final RollupService rollups;

    public QuickAddService(PersonRepository people, PersonService personService, ClaimService claims,
            RollupService rollups) {
        this.people = people;
        this.personService = personService;
        this.claims = claims;
        this.rollups = rollups;
    }

    /** Add a claim of the amount, dated the sheet's entry date (else its today) with an empty note, for the named person. */
    @Transactional
    public Added add(String sheetId, String user, String name, BigDecimal amount) {
        String trimmed = name.trim();
        Person person = rollups.track(sheetId, user, () -> people.findByName(sheetId, trimmed)
                .orElseGet(() -> personService.add(sheetId, user, trimmed, GRADE)));
        Claim claim = rollups.track(sheetId, user, () -> claims.add(sheetId, user, person.getId(), amount, null, "", null));
        return new Added(person, claim);
    }
}
