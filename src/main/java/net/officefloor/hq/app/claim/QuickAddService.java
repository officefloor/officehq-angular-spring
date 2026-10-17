package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The quick-add bar: a DRAFT claim of an amount, dated the sheet's today with an empty note, for the
 * person of a name (compared like uniqueness on the sheet). A name not on the sheet adds the person
 * first, with grade A. Both are one change: if the claim is refused, the person is not added either.
 */
@Service
public class QuickAddService {

    private static final String NEW_GRADE = "A";

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

    @Transactional
    public Claim add(String sheetId, String user, String name, BigDecimal amount) {
        sheets.load(sheetId, user);
        // Lock the sheet, so the same new name quick-added at the same moment adds one person.
        sheets.lock(sheetId);
        String trimmed = name.trim();
        Person person = people.findFirstBySheetIdAndNameIgnoreCase(sheetId, trimmed)
                .orElseGet(() -> personService.add(sheetId, user, trimmed, NEW_GRADE));
        return claimService.add(sheetId, user, person.getId(), amount, null, "");
    }
}
