package net.officefloor.hq.app.duplicate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finds the claims a person has more than once: the same date and amount. */
@Service
public class DuplicateClaimService {

    /** A person's date and amount with two or more claims (VOID and DELETED ones left out). */
    public record Duplicate(String name, String date, String amount, int count) {
    }

    private record Key(LocalDate date, BigDecimal amount) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byDate = date.compareTo(other.date);
            return byDate != 0 ? byDate : amount.compareTo(other.amount);
        }
    }

    private final PersonRepository people;
    private final ClaimRepository claims;

    public DuplicateClaimService(PersonRepository people, ClaimRepository claims) {
        this.people = people;
        this.claims = claims;
    }

    /** The sheet's duplicate claims: people in list order, then by date, then by amount. */
    @Transactional(readOnly = true)
    public List<Duplicate> duplicates(String sheetId) {
        Map<Long, List<Claim>> byPerson = claims.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .collect(Collectors.groupingBy(Claim::getPersonId));
        List<Duplicate> found = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            Map<Key, Integer> counts = new TreeMap<>();
            for (Claim claim : byPerson.getOrDefault(person.getId(), List.of())) {
                counts.merge(new Key(claim.getDate(), claim.getAmount().stripTrailingZeros()), 1, Integer::sum);
            }
            counts.forEach((key, count) -> {
                if (count >= 2) {
                    found.add(new Duplicate(person.getName(), key.date().toString(),
                            key.amount().setScale(2).toPlainString(), count));
                }
            });
        }
        return found;
    }
}
