package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rate.GradeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quick-add: a DRAFT claim of an amount, dated the sheet's entry date while set (else its today) with an empty note, for the person
 * of a name or an alias, matched as the person form matches names; a name not on the sheet adds the person first
 * with the sheet's default grade (A when none is set or it is retired). The whole of it is one change: a refused claim leaves no new person behind.
 */
@Service
public class QuickAddService {

    private final PersonService persons;
    private final ClaimService claims;
    private final GradeService grades;

    public QuickAddService(PersonService persons, ClaimService claims, GradeService grades) {
        this.persons = persons;
        this.claims = claims;
        this.grades = grades;
    }

    /** Returns the person the claim belongs to. */
    @Transactional
    public long add(String sheetId, String user, String name, BigDecimal amount) {
        String trimmed = name.trim();
        Person person = persons.findByNameOrAlias(sheetId, trimmed)
                .orElseGet(() -> persons.add(sheetId, user, trimmed, grades.defaultGrade(sheetId), null, null));
        return claims.add(sheetId, user, person.getId(), amount, null, "");
    }
}
