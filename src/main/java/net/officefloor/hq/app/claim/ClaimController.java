package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing, adding and editing the claims on a sheet. */
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    /** An amount: 0.00 or more, with up to two decimals. */
    private static final String AMOUNT = "\\d{1,12}(\\.\\d{1,2})?";

    public record ListClaimsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record AddClaimRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long personId, @NotNull @Pattern(regexp = AMOUNT) String amount, LocalDate date,
            @Size(max = 1000) String note) {
    }

    public record UpdateClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotNull @Pattern(regexp = AMOUNT) String amount,
            LocalDate date, @Size(max = 1000) String note) {
    }

    public record ClaimResponse(long id, long personId, String amount, LocalDate date, String note) {
    }

    private static ClaimResponse response(Claim claim) {
        return new ClaimResponse(claim.getId(), claim.getPersonId(),
                claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), claim.getDate(),
                claim.getNote());
    }

    private static BigDecimal amount(String amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String note(String note) {
        return note == null ? "" : note.trim();
    }

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<ClaimResponse> list(@Valid @RequestBody ListClaimsRequest req) {
        return service.list(req.sheet().trim()).stream().map(ClaimController::response).toList();
    }

    @PostMapping("/add")
    public ClaimResponse add(@Valid @RequestBody AddClaimRequest req) {
        return response(service.add(req.sheet().trim(), req.personId(), amount(req.amount()), req.date(),
                note(req.note())));
    }

    @PostMapping("/update")
    public ClaimResponse update(@Valid @RequestBody UpdateClaimRequest req) {
        return response(service.update(req.sheet().trim(), req.id(), amount(req.amount()), req.date(),
                note(req.note())));
    }
}
