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
 * claims left out), one line, people in list order, then by date, then by amount.
 */
@Service
public class DuplicateClaimsService {

    /** A duplicate: the person's name, the date, the amount with two decimals and how many claims share them. */
    public record Duplicate(String name, String date, String amount, int count) {
    }

    private record Key(LocalDate date, BigDecimal amount) {
    }

    private static final Comparator<Key> ORDER = Comparator.comparing(Key::date).thenComparing(Key::amount);

    private final PersonRepository people;
    private final ClaimRepository claims;

    public DuplicateClaimsService(PersonRepository people, ClaimRepository claims) {
        this.people = people;
        this.claims = claims;
    }

    @Transactional(readOnly = true)
    public List<Duplicate> duplicates(String sheetId) {
        Map<Long, List<Claim>> byPerson = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .collect(Collectors.groupingBy(Claim::getPersonId));
        List<Duplicate> found = new ArrayList<>();
        for (Person person : people.findInListOrder(sheetId)) {
            List<Claim> theirs = byPerson.get(person.getId());
            if (theirs == null) continue;
            Map<Key, Long> counts = theirs.stream().collect(Collectors.groupingBy(
                    c -> new Key(c.getDate(), c.getAmount().setScale(2, RoundingMode.HALF_EVEN)),
                    () -> new TreeMap<>(ORDER), Collectors.counting()));
            counts.forEach((key, count) -> {
                if (count >= 2) found.add(new Duplicate(person.getName(), key.date().toString(),
                        key.amount().toPlainString(), count.intValue()));
            });
        }
        return found;
    }
}
