package net.officefloor.hq.app.quick;

import java.math.BigDecimal;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.role.RoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick-add: a DRAFT claim of an amount, dated the sheet's today with an empty note, for the person
 * of a name (compared without regard to case), adding the person with grade A first when the name is
 * not on the sheet. It is one change: a refused claim leaves no new person behind and writes nothing.
 */
@Service
public class QuickAddService {

    /** The grade a person added by quick-add takes. */
    private static final String GRADE = "A";

    public record Added(Person person, boolean created, Claim claim) {
    }

    private final PersonRepository people;
    private final PersonService personService;
    private final ClaimService claimService;
    private final RoleService roles;

    public QuickAddService(PersonRepository people, PersonService personService, ClaimService claimService,
            RoleService roles) {
        this.roles = roles;
        this.people = people;
        this.personService = personService;
        this.claimService = claimService;
    }

    @Transactional
    public Added add(String sheetId, String user, String name, BigDecimal amount) {
        roles.refuseViewer(sheetId, user);
        Person existing = people.findBySheetIdAndNameIgnoreCase(sheetId, name).orElse(null);
        Person person = existing != null ? existing : personService.add(sheetId, user, name, GRADE, null);
        Claim claim = claimService.add(sheetId, user, person.getId(), amount, null, "", null, null);
        return new Added(person, existing == null, claim);
    }
}
