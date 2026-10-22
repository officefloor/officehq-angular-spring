package net.officefloor.hq.app.quickadd;

import java.math.BigDecimal;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The quick-add bar: a DRAFT claim of an amount, dated the sheet's entry date while set (else its today) with an empty note, for the
 * person of a name, who is first added with grade A when the name is not on the sheet. It is one
 * change: when the claim is refused the person added for it is not kept, and nothing is recorded.
 */
@Service
public class QuickAddService {

    /** The grade a person added by the quick-add bar is given. */
    static final String GRADE = "A";

    /** What a quick-add made: the claim, for its person, and whether that person was added for it. */
    public record Added(Person person, boolean personAdded, Claim claim) {
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
        Person existing = people.findBySheetIdAndName(sheetId, name).orElse(null);
        Person person = existing != null ? existing : personService.add(sheetId, user, name, GRADE, null, null);
        Claim claim = claimService.add(sheetId, user, person.getId(), amount, null, "", null);
        return new Added(person, existing == null, claim);
    }
}
