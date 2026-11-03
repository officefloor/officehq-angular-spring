package net.officefloor.hq.app.quickadd;

import java.math.BigDecimal;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Quick-add: a draft claim for a person named on the sheet, who is added first (at the sheet's default grade) when
 *  the name is not on the sheet. */
@Service
public class QuickAddService {

    /** What a quick-add made: the person the claim is for, whether they were added, and the claim. */
    public record Added(Person person, boolean personAdded, Claim claim) {
    }

    private final PersonRepository people;
    private final PersonService personService;
    private final ClaimService claimService;
    private final SheetService sheets;

    public QuickAddService(PersonRepository people, PersonService personService, ClaimService claimService,
            SheetService sheets) {
        this.people = people;
        this.personService = personService;
        this.claimService = claimService;
        this.sheets = sheets;
    }

    /** Add a draft claim of the amount, dated the sheet's entry date (or its today when none is set)
     *  with an empty note, for the person of
     *  the name. One change: when the claim is refused, the person added for it is rolled back too
     *  and nothing is recorded. */
    @Transactional
    public Added add(String sheetId, String user, String name, BigDecimal amount) {
        Person existing = people.findByNameOrAlias(sheetId, name).orElse(null);
        Person person = existing != null ? existing : personService.add(sheetId, user, name, sheets.defaultGrade(sheetId), null);
        Claim claim = claimService.add(sheetId, user, person.getId(), amount, null, "");
        return new Added(person, existing == null, claim);
    }
}
