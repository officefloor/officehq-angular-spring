package net.officefloor.hq.app.claim;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sheet's duplicate claims: for each person, date and amount with two or more claims (VOID and
 * DELETED claims left out), one line {@code <name> <date> <amount> x<count>}, people in list order,
 * then by date, then by amount.
 */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/duplicate-claims")
public class DuplicateClaimsController {

    private record Key(LocalDate date, BigDecimal amount) {}

    private static final Comparator<Key> ORDER =
            Comparator.comparing(Key::date).thenComparing(Key::amount);

    private final PersonRepository people;
    private final ClaimRepository claims;

    public DuplicateClaimsController(PersonRepository people, ClaimRepository claims) {
        this.people = people;
        this.claims = claims;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<String> duplicates(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        Map<Long, List<Claim>> byPerson = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .collect(Collectors.groupingBy(Claim::getPersonId));
        List<String> lines = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            Map<Key, Integer> counts = new TreeMap<>(ORDER);
            for (Claim claim : byPerson.getOrDefault(person.getId(), List.of())) {
                counts.merge(new Key(claim.getDate(), claim.getAmount().stripTrailingZeros()), 1, Integer::sum);
            }
            counts.forEach((key, count) -> {
                if (count >= 2) {
                    lines.add(person.getName() + " " + key.date() + " "
                            + key.amount().setScale(2).toPlainString() + " x" + count);
                }
            });
        }
        return lines;
    }
}
