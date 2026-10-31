package net.officefloor.hq.app.quickadd;

import java.math.BigDecimal;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The quick-add bar: a DRAFT claim for a person named by typed text, adding the person if new. */
@Service
public class QuickAddService {

    /** The grade a person added by the quick-add bar is given. */
    static final String NEW_PERSON_GRADE = "A";

    /** What a quick-add made: the person the claim is for (new or already on the sheet) and the claim. */
    public record Added(Person person, boolean personAdded, Claim claim) {
    }

    private final PersonRepository people;
    private final PersonService personService;
    private final ClaimService claims;

    public QuickAddService(PersonRepository people, PersonService personService, ClaimService claims) {
        this.people = people;
        this.personService = personService;
        this.claims = claims;
    }

    /**
     * Add a DRAFT claim of {@code amount}, dated the sheet's entry date while set, else its today, with an empty note, for the person
     * whose name matches {@code name} (trimmed, ignoring case); one not on the sheet is added first with
     * grade A. One change: when the claim is refused the person is not added and nothing is recorded.
     */
    @Transactional
    public Added add(String sheetId, String user, String name, BigDecimal amount) {
        Person existing = people.findBySheetIdAndNameIgnoreCase(sheetId, name.trim()).orElse(null);
        Person person = existing != null ? existing : personService.add(sheetId, user, name, NEW_PERSON_GRADE);
        Claim claim = claims.add(sheetId, user, person.getId(), amount, null, "", null);
        return new Added(person, existing == null, claim);
    }
}
