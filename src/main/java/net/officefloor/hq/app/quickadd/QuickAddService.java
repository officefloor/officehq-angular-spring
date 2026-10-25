package net.officefloor.hq.app.quickadd;

import java.math.BigDecimal;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuickAddService {

    /** The person the claim was added to, and the claim. */
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

    /** Add a draft claim of the amount, dated the sheet's today with an empty note, to the person of
     *  that name, adding the person with grade A first when no one on the sheet has it. One change: a
     *  refused claim rolls back the person added for it, and writes nothing. */
    @Transactional
    public Added add(String sheetId, String user, String name, BigDecimal amount) {
        Person person = people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .orElseGet(() -> personService.add(sheetId, user, name, Grade.A, null));
        Claim claim = claimService.add(sheetId, user, person.getId(), amount, null, "", null);
        return new Added(person, claim);
    }
}
