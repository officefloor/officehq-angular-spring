package net.officefloor.hq.app.claim;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The sheet's duplicate claims: claims of one person sharing a date and an amount. Reading them records nothing. */
@RestController
@Validated
public class DuplicateClaimsController {

    /** A person's claims of one date and amount. */
    private record Key(LocalDate date, BigDecimal amount) {
    }

    private static final Comparator<Key> BY_DATE_THEN_AMOUNT =
            Comparator.comparing(Key::date).thenComparing(Key::amount);

    private final PersonRepository people;
    private final ClaimRepository claims;

    public DuplicateClaimsController(PersonRepository people, ClaimRepository claims) {
        this.people = people;
        this.claims = claims;
    }

    /** One line {@code <name> <date> <amount> x<count>} for each person, date and amount with two or more claims,
     *  VOID and DELETED claims left out: people in list order, then by date, then by amount. Read from the store as
     *  it holds them now. */
    @GetMapping("/api/sheets/{sheetId}/duplicates")
    @Transactional(readOnly = true)
    public List<String> duplicates(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        Map<Long, List<Claim>> byPerson = claims.findBySheetIdOrderByListedDescIdDesc(sheetId).stream()
                .filter(c -> c.getStatus() != ClaimStatus.VOID && c.getStatus() != ClaimStatus.DELETED)
                .collect(Collectors.groupingBy(Claim::getPersonId));
        List<String> lines = new ArrayList<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            Map<Key, Integer> counts = new TreeMap<>(BY_DATE_THEN_AMOUNT);
            for (Claim c : byPerson.getOrDefault(person.getId(), List.of())) {
                counts.merge(new Key(c.getDate(), c.getAmount().setScale(2, RoundingMode.HALF_UP)), 1, Integer::sum);
            }
            counts.forEach((key, n) -> {
                if (n >= 2) {
                    lines.add(person.getName() + " " + key.date() + " " + key.amount().toPlainString() + " x" + n);
                }
            });
        }
        return lines;
    }
}
