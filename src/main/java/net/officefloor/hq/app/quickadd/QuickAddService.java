package net.officefloor.hq.app.quickadd;

import java.math.BigDecimal;
import java.util.Locale;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuickAddService {

    private final PersonRepository people;
    private final PersonService persons;
    private final ClaimService claims;

    public QuickAddService(PersonRepository people, PersonService persons, ClaimService claims) {
        this.people = people;
        this.persons = persons;
        this.claims = claims;
    }

    /**
     * Add a draft claim of this amount, dated the sheet's today with an empty note, for the person
     * with this name (ignoring case); one not on the sheet is added first with grade A. It is one
     * change: when the claim is refused, the person added for it is not kept and nothing is audited.
     */
    @Transactional
    public Claim add(String sheetId, String user, String name, BigDecimal amount) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        Person person = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> p.getName().toLowerCase(Locale.ROOT).equals(key))
                .findFirst()
                .orElseGet(() -> persons.add(sheetId, user, name, Grade.A));
        return claims.add(sheetId, user, person.getId(), amount, null, "");
    }
}
