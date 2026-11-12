package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds a DRAFT claim, dated the sheet's entry date (while set, else its today) with an empty note, for a person named by typed name; a name not on
 * the sheet first adds the person with the sheet's default grade. The whole is one change: a refused claim leaves no new person.
 */
@Service
public class QuickAddService {

    private final PersonService persons;
    private final ClaimService claims;

    public QuickAddService(PersonService persons, ClaimService claims) {
        this.persons = persons;
        this.claims = claims;
    }

    @Transactional
    public Claim add(String sheetId, String user, String name, BigDecimal amount) {
        Person person = persons.findByNameOrAlias(sheetId, name)
                .orElseGet(() -> persons.add(sheetId, user, name, persons.defaultGrade(sheetId), null));
        return claims.add(sheetId, user, person.getId(), amount, null, "", null);
    }
}
