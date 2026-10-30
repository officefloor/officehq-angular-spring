package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick-add: a DRAFT claim of an amount, dated the sheet's entry date while set (else its today) with an empty note, for the person
 * of a name, matched as the person form matches names; a name not on the sheet adds the person first
 * with grade A. The whole of it is one change: a refused claim leaves no new person behind.
 */
@Service
public class QuickAddService {

    private static final String GRADE = "A";

    private final PersonRepository people;
    private final PersonService persons;
    private final ClaimService claims;

    public QuickAddService(PersonRepository people, PersonService persons, ClaimService claims) {
        this.people = people;
        this.persons = persons;
        this.claims = claims;
    }

    /** Returns the person the claim belongs to. */
    @Transactional
    public long add(String sheetId, String user, String name, BigDecimal amount) {
        String trimmed = name.trim();
        Person person = people.findBySheetIdAndNameIgnoreCase(sheetId, trimmed)
                .orElseGet(() -> persons.add(sheetId, user, trimmed, GRADE, null, null));
        return claims.add(sheetId, user, person.getId(), amount, null, "");
    }
}
