package net.officefloor.hq.app.claim;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Component;

/** Puts a selection of a sheet's claims in list order: people in list order (by position), each
 *  person's claims newest first. */
@Component
public class ClaimListOrder {

    private final ClaimRepository claims;
    private final PersonRepository people;

    public ClaimListOrder(ClaimRepository claims, PersonRepository people) {
        this.claims = claims;
        this.people = people;
    }

    /** The selected claims on the sheet, in list order; an id not on the sheet is left out. */
    public List<Claim> selected(String sheetId, Set<Long> ids) {
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> sheetPeople = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < sheetPeople.size(); i++) {
            personOrder.put(sheetPeople.get(i).getId(), i);
        }
        return claims.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(claim -> ids.contains(claim.getId()))
                .sorted(Comparator.comparing((Claim claim) -> personOrder.getOrDefault(claim.getPersonId(), Integer.MAX_VALUE)))
                .toList();
    }
}
