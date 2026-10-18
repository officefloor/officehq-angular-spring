package net.officefloor.hq.app.quickadd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import net.officefloor.hq.app.session.SessionController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/quick-add")
public class QuickAddController {

    /** The person's name and form rules are the person form's, the amount's the claim form's. */
    public record QuickAddForm(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\d{1,13}(\\.\\d{1,2})?") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount).setScale(2);
        }
    }

    private final QuickAddService service;

    public QuickAddController(QuickAddService service) {
        this.service = service;
    }

    /** Add a draft claim for the named person, adding them (grade A) when they are not on the sheet. */
    @PostMapping
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String sheetId,
            @RequestHeader(SessionController.USER_HEADER) @NotBlank String user,
            @Valid @RequestBody QuickAddForm form) {
        return ClaimView.of(service.add(sheetId, user, form.name(), form.amountValue()));
    }
}
