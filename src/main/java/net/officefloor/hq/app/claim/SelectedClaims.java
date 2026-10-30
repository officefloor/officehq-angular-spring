package net.officefloor.hq.app.claim;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Component;

/**
 * A selection of claims put in list order, as a bulk action goes through them: the people in the
 * order they were added, each person's newest claim first.
 */
@Component
public class SelectedClaims {

    private final ClaimRepository claimRows;
    private final PersonRepository people;

    public SelectedClaims(ClaimRepository claimRows, PersonRepository people) {
        this.claimRows = claimRows;
        this.people = people;
    }

    /** The selected claims on the sheet, in list order; an id not on the sheet is left out. */
    public List<Claim> inListOrder(String sheetId, Set<Long> claimIds) {
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) {
            personOrder.put(listed.get(i).getId(), i);
        }
        return claimRows.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> claimIds.contains(c.getId()))
                .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getListed, Comparator.reverseOrder()))
                .toList();
    }
}
