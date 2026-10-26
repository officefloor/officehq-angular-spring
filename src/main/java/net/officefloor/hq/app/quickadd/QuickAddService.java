package net.officefloor.hq.app.quickadd;

import java.math.BigDecimal;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds a draft claim for a person named on the sheet, adding the person first when the name is new. */
@Service
public class QuickAddService {

    /** What a quick-add made: the person (new or found), whether they were added, and their new claim. */
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

    /** One change: a refused claim rolls back the person added for it, and its audit records with it. */
    @Transactional
    public Added add(String by, String sheetId, String name, BigDecimal amount) {
        String trimmed = name.strip();
        Person found = people.findBySheetIdAndNameIgnoreCase(sheetId, trimmed).orElse(null);
        Person person = found != null ? found : personService.add(by, sheetId, trimmed, "A");
        Claim claim = claimService.add(by, sheetId, person.getId(), amount, null, "", null);
        return new Added(person, found == null, claim);
    }
}
