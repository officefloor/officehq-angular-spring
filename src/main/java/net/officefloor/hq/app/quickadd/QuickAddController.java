package net.officefloor.hq.app.quickadd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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

/** The quick-add bar. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/quick-add")
public class QuickAddController {

    /** A name and an amount as the bar submits them, under the person and claim forms' rules. */
    public record QuickAddForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal amount) {
    }

    /** The person the claim is for, whether they were added by this quick-add, and the claim. */
    public record QuickAddView(PersonView person, boolean personAdded, ClaimView claim) {
    }

    private final QuickAddService quickAdd;

    public QuickAddController(QuickAddService quickAdd) {
        this.quickAdd = quickAdd;
    }

    @PostMapping
    public QuickAddView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody QuickAddForm request) {
        QuickAddService.Added added = quickAdd.add(sheetId, user, request.name(), request.amount());
        return new QuickAddView(PersonView.of(added.person()), added.personAdded(), ClaimView.of(added.claim()));
    }
}
