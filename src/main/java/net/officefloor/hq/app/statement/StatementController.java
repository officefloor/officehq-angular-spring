package net.officefloor.hq.app.statement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimController.ClaimView;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/people/{personId}/statement")
public class StatementController {

    /** A statement taken: the person's statements as their row shows them, and their claims,
     *  newest first, as it bound them. */
    public record StatementTaken(StatementSummary statements, List<ClaimView> claims) {
    }

    private final StatementService service;

    private final RollupService rollups;

    private final ClaimService claimService;

    public StatementController(StatementService service, RollupService rollups, ClaimService claimService) {
        this.service = service;
        this.rollups = rollups;
        this.claimService = claimService;
    }

    /** Take a statement for the person. */
    @PostMapping
    public StatementTaken take(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long personId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user) {
        String sheet = sheetId.trim();
        String by = SignedInUser.decode(user);
        List<ClaimView> claims = rollups.track(sheet, by, () -> service.take(sheet, by, personId)).stream()
                .map(claim -> ClaimView.of(claim, claimService.roundsBy(sheet, claim))).toList();
        return new StatementTaken(service.summary(sheet, personId), claims);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }
}
