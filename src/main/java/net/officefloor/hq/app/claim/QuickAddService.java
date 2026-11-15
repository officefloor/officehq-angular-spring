package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuickAddService {

    /** The grade a person the quick-add creates is given. */
    static final String NEW_PERSON_GRADE = "A";

    /** A quick-add's result: the person the claim is for (perhaps just created) and the claim. */
    public record QuickAdd(Person person, boolean personAdded, Claim claim) {
    }

    private final PersonRepository people;
    private final PersonService personService;
    private final ClaimService claimService;

    public QuickAddService(PersonRepository people, PersonService personService, ClaimService claimService) {
        this.people = people;
        this.personService = personService;
        this.claimService = claimService;
    }

    /** Add a DRAFT claim dated the sheet's entry date (while set, else its today) with an empty note for the person with this name
     *  (ignoring case and surrounding spaces), adding them with grade A first when the sheet has no
     *  such person. One change: when the claim is refused, the person is not added either. */
    @Transactional
    public QuickAdd add(String sheetId, String user, String name, BigDecimal amount) {
        String trimmed = name.trim();
        Person person = people.findBySheetIdAndNameIgnoreCase(sheetId, trimmed).stream().findFirst().orElse(null);
        boolean added = person == null;
        if (added) {
            person = personService.add(sheetId, user, trimmed, NEW_PERSON_GRADE, null);
        }
        Claim claim = claimService.add(sheetId, user, person.getId(), amount, null, "", null, null);
        return new QuickAdd(person, added, claim);
    }
}
