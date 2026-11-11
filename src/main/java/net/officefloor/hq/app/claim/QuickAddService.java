package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds a DRAFT claim, dated the sheet's today with an empty note, for a person named by typed name; a name not on
 * the sheet first adds the person with grade A. The whole is one change: a refused claim leaves no new person.
 */
@Service
public class QuickAddService {

    static final String NEW_PERSON_GRADE = "A";

    private final PersonRepository people;
    private final PersonService persons;
    private final ClaimService claims;

    public QuickAddService(PersonRepository people, PersonService persons, ClaimService claims) {
        this.people = people;
        this.persons = persons;
        this.claims = claims;
    }

    @Transactional
    public Claim add(String sheetId, String user, String name, BigDecimal amount) {
        Person person = people.findBySheetIdAndNameKey(sheetId, Person.key(name))
                .orElseGet(() -> persons.add(sheetId, user, name, NEW_PERSON_GRADE));
        return claims.add(sheetId, user, person.getId(), amount, null, "");
    }
}
