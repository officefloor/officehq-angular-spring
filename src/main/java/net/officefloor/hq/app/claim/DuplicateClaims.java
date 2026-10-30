package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sheet's duplicate claims: for each person, date and amount with two or more claims (VOID and DELETED
 * claims left out), people in list order, then by date, then by amount. It reads the store; it records nothing.
 */
@Service
public class DuplicateClaims {

    /** A person, date and amount with {@code count} claims; the amount as shown, with two decimals. */
    public record Duplicate(String name, LocalDate date, String amount, int count) {
    }

    private record Key(LocalDate date, BigDecimal amount) {
    }

    private static final Comparator<Key> ORDER = Comparator.comparing(Key::date)
            .thenComparing(Key::amount);

    private final ClaimRepository claims;
    private final PersonRepository people;

    public DuplicateClaims(ClaimRepository claims, PersonRepository people) {
        this.claims = claims;
        this.people = people;
    }

    @Transactional(readOnly = true)
    public List<Duplicate> find(String sheetId) {
        Map<Long, List<Claim>> byPerson = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .collect(Collectors.groupingBy(Claim::getPersonId));
        List<Duplicate> found = new ArrayList<>();
        for (Person p : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            Map<Key, Integer> counts = new TreeMap<>(ORDER);
            for (Claim c : byPerson.getOrDefault(p.getId(), List.of())) {
                Key key = new Key(c.getDate(), c.getAmount().setScale(2, RoundingMode.HALF_UP));
                counts.merge(key, 1, Integer::sum);
            }
            counts.forEach((key, count) -> {
                if (count >= 2) {
                    found.add(new Duplicate(p.getName(), key.date(), key.amount().toPlainString(), count));
                }
            });
        }
        return found;
    }
}
