package net.officefloor.hq.app.quickadd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.person.PersonController.PersonView;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class QuickAddController {

    /** The person the claim went to, whether they were added for it, and the claim. */
    public record QuickAddView(PersonView person, boolean added, ClaimView claim) {}

    /** A person's name, as the person form takes it, and a claim's amount, as the claim form takes it. */
    public record QuickAddRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\d{1,12}(\\.\\d{1,2})?") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
        }
    }

    private final QuickAddService service;

    public QuickAddController(QuickAddService service) {
        this.service = service;
    }

    @PostMapping("/quick-add")
    public QuickAddView add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid QuickAddRequest request) {
        QuickAddService.QuickAdded done = service.add(sheetId, user, request.name().strip(), request.amountValue());
        return new QuickAddView(PersonView.of(done.person()), done.added(), ClaimView.of(done.claim()));
    }
}
