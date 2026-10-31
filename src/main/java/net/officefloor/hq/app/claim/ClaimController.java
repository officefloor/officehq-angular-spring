package net.officefloor.hq.app.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Claims on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}")
public class ClaimController {

    /**
     * A claim as the screen shows it; the amount is given with exactly two decimals, as is the due
     * fixed when it was settled (null while it is not settled, or if its grade had no rate then); openedBy
     * is who opened it (null while a draft); stated is whether its person's last statement binds it, to
     * statedDue (null if it had no figure then, or while PENDING, when no statement binds it); corrects is the number of the claim a correction corrects (null
     * on any other claim).
     */
    public record ClaimView(long id, long personId, String amount, LocalDate date, String note, ClaimStatus status,
            String number, String settledDue, String openedBy, boolean stated, String statedDue, String corrects) {

        public static ClaimView of(Claim claim) {
            return new ClaimView(claim.getId(), claim.getPersonId(), claim.getAmount().toPlainString(),
                    claim.getDate(), claim.getNote(), claim.getStatus(), claim.getNumber(),
                    claim.getSettledDue() == null ? null : claim.getSettledDue().toPlainString(),
                    claim.getOpenedBy(), claim.isStated(),
                    claim.getBoundDue() == null ? null : claim.getBoundDue().toPlainString(), claim.getCorrects());
        }
    }

    /** A claim's values as the form submits them; a missing date takes the sheet's today. A negative amount is a
     *  correction, which names in corrects the number of the claim it corrects. */
    public record ClaimForm(
            @NotNull @Digits(integer = 12, fraction = 2) BigDecimal amount,
            LocalDate date,
            @Size(max = 1000) String note,
            @Size(max = 120) String corrects) {
    }

    /** The status a claim is to move to. */
    public record StatusChange(@NotNull ClaimStatus status) {
    }

    /** Why a steward rejects a PENDING claim. */
    public record Rejection(@NotBlank @Size(max = 200) @Pattern(regexp = "\\S+( \\S+)*") String reason) {
    }

    /** The claims selected to be opened together. */
    public record BulkOpen(@NotNull @Size(max = 1000) List<@NotNull Long> ids) {
    }

    /** What a bulk open did: how many it opened and refused, and the claims it opened, in the order opened. */
    public record BulkOpenView(int opened, int refused, List<ClaimView> claims) {
    }

    private final ClaimService claims;

    public ClaimController(ClaimService claims) {
        this.claims = claims;
    }

    @GetMapping("/claims")
    public List<ClaimView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return claims.list(sheetId).stream().map(ClaimView::of).toList();
    }

    @PostMapping("/people/{personId}/claims")
    public ClaimView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long personId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm request) {
        return ClaimView.of(claims.add(sheetId, user, personId, request.amount(), request.date(), request.note(),
                request.corrects()));
    }

    @PutMapping("/claims/{id}")
    public ClaimView update(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody ClaimForm request) {
        return ClaimView.of(claims.update(sheetId, user, id, request.amount(), request.date(), request.note(),
                request.corrects()));
    }

    @PutMapping("/claims/{id}/status")
    public ClaimView changeStatus(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody StatusChange request) {
        return ClaimView.of(claims.changeStatus(sheetId, user, id, request.status()));
    }

    @PutMapping("/claims/{id}/approve")
    public ClaimView approve(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return ClaimView.of(claims.approve(sheetId, user, id));
    }

    @PutMapping("/claims/{id}/reject")
    public ClaimView reject(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long id,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody Rejection request) {
        return ClaimView.of(claims.reject(sheetId, user, id, request.reason()));
    }

    /**
     * Open each selected claim that can be opened, one at a time in list order (people in the order listed,
     * newest claim first within a person), so they are numbered in that order; each that cannot be opened, or is
     * not on the sheet, is refused and left as it is.
     */
    @PostMapping("/claims/bulk-open")
    public BulkOpenView bulkOpen(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody BulkOpen request) {
        List<Long> ordered = claims.inListOrder(sheetId, request.ids());
        int refused = (int) request.ids().stream().distinct().count() - ordered.size();
        List<ClaimView> opened = new ArrayList<>();
        for (long id : ordered) {
            try {
                opened.add(ClaimView.of(claims.changeStatus(sheetId, user, id, ClaimStatus.OPEN)));
            } catch (ResponseStatusException refusal) {
                refused++;
            }
        }
        return new BulkOpenView(opened.size(), refused, opened);
    }
}
