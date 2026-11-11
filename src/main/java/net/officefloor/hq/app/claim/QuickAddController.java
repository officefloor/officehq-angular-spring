package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The quick-add bar: a claim for a person by name. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/quick-add")
@Validated
public class QuickAddController {

    /** The person's name, as the person form takes it, and an amount, as the claim form takes it. */
    public record QuickAddRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "\\s*\\d{1,10}(\\.\\d{1,2})?\\s*") String amount) {

        BigDecimal amountValue() {
            return new BigDecimal(amount.trim()).setScale(2, RoundingMode.UNNECESSARY);
        }
    }

    private final QuickAddService service;
    private final ClaimService claims;

    public QuickAddController(QuickAddService service, ClaimService claims) {
        this.service = service;
        this.claims = claims;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid QuickAddRequest request) {
        Claim claim = service.add(sheetId.trim(), user.trim(), request.name(), request.amountValue());
        return ClaimView.of(claim, claims.logOf(claim));
    }
}
