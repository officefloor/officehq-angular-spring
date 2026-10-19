package net.officefloor.hq.app.quickadd;

import java.math.BigDecimal;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuickAddService {

    /** The grade a person the quick-add creates is given. */
    static final String GRADE = "A";

    public record Added(Person person, boolean personCreated, Claim claim) {}

    private final PersonRepository people;
    private final PersonService personService;
    private final ClaimService claimService;

    public QuickAddService(PersonRepository people, PersonService personService, ClaimService claimService) {
        this.people = people;
        this.personService = personService;
        this.claimService = claimService;
    }

    /** Add a draft claim dated the sheet's today with an empty note for the named person, creating them with
     * grade A first when the name is not on the sheet. It is one change: a refused claim leaves no new person. */
    @Transactional
    public Added add(String user, String sheetId, String name, BigDecimal amount) {
        String trimmed = name.trim();
        Person person = people.findByName(sheetId, trimmed).orElse(null);
        boolean created = person == null;
        if (created) {
            person = personService.add(user, sheetId, trimmed, GRADE);
        }
        Claim claim = claimService.add(user, sheetId, person.getId(), amount, null, "", null);
        return new Added(person, created, claim);
    }
}
