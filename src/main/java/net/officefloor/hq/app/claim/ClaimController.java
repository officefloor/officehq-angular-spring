package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.person.PersonController.PersonView;
import net.officefloor.hq.app.person.PersonService;
import net.officefloor.hq.app.rate.RateService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's claims. Adding or editing a claim answers with the person it belongs to, carrying
 * their claims and claimed; so does opening, settling or voiding one. The signed-in user's name comes in the {@code X-User} header.
 */
@RestController
@RequestMapping("/api/sheets/{sheetId}")
@Validated
public class ClaimController {

    /** An amount is 0.00 or more with up to two decimals; a blank date takes the sheet's today. */
    public record ClaimForm(@NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount,
            LocalDate date, @Size(max = 500) String note) {
    }

    private final ClaimService claims;
    private final PersonService people;
    private final RateService rates;

    public ClaimController(ClaimService claims, PersonService people, RateService rates) {
        this.claims = claims;
        this.people = people;
        this.rates = rates;
    }

    @PostMapping("/people/{personId}/claims")
    public PersonView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long personId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody ClaimForm form) {
        String sheet = sheetId.trim();
        long id = claims.add(sheet, user.trim(), personId, new BigDecimal(form.amount()), form.date(), form.note());
        return view(sheet, id);
    }

    @PutMapping("/claims/{claimId}")
    public PersonView edit(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user,
            @Valid @RequestBody ClaimForm form) {
        String sheet = sheetId.trim();
        long id = claims.edit(sheet, user.trim(), claimId, new BigDecimal(form.amount()), form.date(), form.note());
        return view(sheet, id);
    }

    @PostMapping("/claims/{claimId}/open")
    public PersonView open(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        return move(sheetId, claimId, user, ClaimStatus.OPEN);
    }

    @PostMapping("/claims/{claimId}/settle")
    public PersonView settle(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        return move(sheetId, claimId, user, ClaimStatus.SETTLED);
    }

    @PostMapping("/claims/{claimId}/void")
    public PersonView voidClaim(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long claimId, @RequestHeader("X-User") @NotBlank String user) {
        return move(sheetId, claimId, user, ClaimStatus.VOID);
    }

    private PersonView move(String sheetId, long claimId, String user, ClaimStatus next) {
        String sheet = sheetId.trim();
        return view(sheet, claims.move(sheet, user.trim(), claimId, next));
    }

    private PersonView view(String sheetId, long personId) {
        return PersonView.of(people.get(sheetId, personId), claims.forPerson(sheetId, personId),
                rates.byGrade(sheetId));
    }
}
