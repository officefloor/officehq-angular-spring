package net.officefloor.hq.app.quickadd;

import java.math.BigDecimal;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds a DRAFT claim, dated the sheet's today with an empty note, for a person named on the sheet,
 * first adding them with grade A when no one has that name. It is one change: when the claim is
 * refused the person is not added either, and nothing is recorded. */
@Service
public class QuickAddService {

    /** The grade a person added by a quick-add is given. */
    static final String GRADE = "A";

    public record Added(Person person, Claim claim) {
    }

    private final PersonRepository people;
    private final PersonService personService;
    private final ClaimService claimService;

    public QuickAddService(PersonRepository people, PersonService personService, ClaimService claimService) {
        this.people = people;
        this.personService = personService;
        this.claimService = claimService;
    }

    @Transactional
    public Added add(String sheetId, String user, String name, BigDecimal amount) {
        Person person = people.findBySheetIdAndNameOrAlias(sheetId, name)
                .orElseGet(() -> personService.add(sheetId, user, name, GRADE, null));
        Claim claim = claimService.add(sheetId, user, person.getId(), amount, null, "", null);
        return new Added(person, claim);
    }
}
