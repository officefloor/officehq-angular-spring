package net.officefloor.hq.app.quickadd;

import java.math.BigDecimal;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick-add: a DRAFT claim of an amount, dated the entry date while set (else the sheet's today) with an empty note, for the person
 * of a name or alias (ignoring case and surrounding spaces); with no such person on the sheet, they are added
 * first with the sheet's default grade (A when blank). It is one change: a refused claim rolls back the person added for it too.
 */
@Service
public class QuickAddService {

    /** The person the claim went to, whether they were added for it, and the claim. */
    public record QuickAdded(Person person, boolean added, Claim claim) {}

    private final PersonRepository people;
    private final PersonService personService;
    private final ClaimService claimService;
    private final SettingService settings;

    public QuickAddService(PersonRepository people, PersonService personService, ClaimService claimService,
            SettingService settings) {
        this.people = people;
        this.personService = personService;
        this.claimService = claimService;
        this.settings = settings;
    }

    @Transactional
    public QuickAdded add(String sheetId, String user, String name, BigDecimal amount) {
        Person existing = people.findByNameOrAlias(sheetId, name).orElse(null);
        Person person = existing != null ? existing : personService.add(sheetId, user, name, settings.gradeForNew(sheetId));
        Claim claim = claimService.add(sheetId, user, person.getId(), amount, null, "", null);
        return new QuickAdded(person, existing == null, claim);
    }
}
