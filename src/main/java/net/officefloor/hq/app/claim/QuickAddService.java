package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The quick-add bar: a DRAFT claim dated the sheet's entry date (its today when off), with an empty note, for the person with
 * the name (ignoring case), adding them with grade A first when the sheet has no one by that name.
 * One change: a refused claim rolls back the person it added, and writes nothing.
 */
@Service
public class QuickAddService {

    /** The grade a person added by the quick-add bar takes. */
    static final String GRADE = "A";

    private final PersonService personService;
    private final PersonRepository people;
    private final ClaimService claims;

    public QuickAddService(PersonService personService, PersonRepository people, ClaimService claims) {
        this.personService = personService;
        this.people = people;
        this.claims = claims;
    }

    /** The person the claim is for, whether they were added, and the claim. */
    public record Added(Person person, boolean personAdded, Claim claim) {
    }

    @Transactional
    public Added add(String sheetId, String user, String name, BigDecimal amount) {
        Person found = people.findByName(sheetId, name.trim()).orElse(null);
        Person person = found != null ? found : personService.add(sheetId, user, name, GRADE, null);
        Claim claim = claims.add(sheetId, user, person.getId(), amount, null, "", null, null);
        return new Added(person, found == null, claim);
    }
}
