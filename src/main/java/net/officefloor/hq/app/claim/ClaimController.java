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

/** Listing, adding and editing the claims on a sheet, and changing their status. */
@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    /** An amount: 0.00 or more, with up to two decimals. */
    public static final String AMOUNT = "\\d{1,12}(\\.\\d{1,2})?";

    /** A claim's amount: as {@link #AMOUNT}, or below zero for a correction. */
    public static final String SIGNED_AMOUNT = "-?" + AMOUNT;

    public record ListClaimsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record AddClaimRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long personId, @NotNull @Pattern(regexp = SIGNED_AMOUNT) String amount, LocalDate date,
            @Size(max = 1000) String note, @Size(max = 120) String corrects) {
    }

    public record UpdateClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id,
            @NotNull @Pattern(regexp = SIGNED_AMOUNT) String amount, LocalDate date, @Size(max = 1000) String note,
            @Size(max = 120) String corrects) {
    }

    public record ClaimStatusRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id) {
    }

    public record RejectClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id,
            @NotBlank @Size(max = 200) @Pattern(regexp = "\\s*\\S+\\s*") String reason) {
    }

    public record ReassignClaimRequest(@NotBlank @Size(max = 100) String sheet,
            @NotBlank @Size(max = 100) String user, @NotNull Long id, @NotNull Long personId) {
    }

    public record BulkOpenRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 1000) List<@NotNull Long> ids) {
    }

    public record BulkOpenResponse(int opened, int refused, List<ClaimResponse> claims) {
    }

    public record BulkSettleResponse(int settled, int refused, List<ClaimResponse> claims) {
    }

    public record ClaimResponse(long id, long personId, String amount, LocalDate date, String note,
            ClaimStatus status, String number, String settledDue, String openedBy,
            boolean stated, String statedDue, String corrects, LocalDate dueBy, List<ClaimLogLine> log) {
    }

    /** One line of a claim's status log, oldest first. */
    public record ClaimLogLine(ClaimStatus status, String by, LocalDate date, String due) {
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    public static ClaimResponse response(Claim claim) {
        return new ClaimResponse(claim.getId(), claim.getPersonId(),
                claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), claim.getDate(),
                claim.getNote(), claim.getStatus(), claim.getNumber(),
                money(claim.getSettledDue()), claim.getOpenedBy(), claim.isStated(), money(claim.getStatedDue()),
                claim.getCorrects(), claim.getDueBy(),
                claim.getLog().stream().map(l -> new ClaimLogLine(l.getStatus(), l.getBy(), l.getDate(), l.getDue()))
                        .toList());
    }

    public static BigDecimal amount(String amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** The number a correction names, trimmed; null when none is named. */
    private static String corrects(String corrects) {
        return corrects == null || corrects.isBlank() ? null : corrects.trim();
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
        return response(service.add(req.sheet().trim(), req.user().trim(), req.personId(), amount(req.amount()), req.date(),
                note(req.note()), corrects(req.corrects())));
    }

    @PostMapping("/update")
    public ClaimResponse update(@Valid @RequestBody UpdateClaimRequest req) {
        return response(service.update(req.sheet().trim(), req.user().trim(), req.id(), amount(req.amount()), req.date(),
                note(req.note()), corrects(req.corrects())));
    }

    @PostMapping("/open")
    public ClaimResponse open(@Valid @RequestBody ClaimStatusRequest req) {
        return changeStatus(req, ClaimStatus.OPEN);
    }

    @PostMapping("/settle")
    public ClaimResponse settle(@Valid @RequestBody ClaimStatusRequest req) {
        return changeStatus(req, ClaimStatus.SETTLED);
    }

    @PostMapping("/approve")
    public ClaimResponse approve(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.approve(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/reject")
    public ClaimResponse reject(@Valid @RequestBody RejectClaimRequest req) {
        return response(service.reject(req.sheet().trim(), req.user().trim(), req.id(), req.reason().trim()));
    }

    @PostMapping("/void")
    public ClaimResponse voidClaim(@Valid @RequestBody ClaimStatusRequest req) {
        return changeStatus(req, ClaimStatus.VOID);
    }

    @PostMapping("/resettle")
    public ClaimResponse resettle(@Valid @RequestBody ClaimStatusRequest req) {
        return response(service.resettle(req.sheet().trim(), req.user().trim(), req.id()));
    }

    @PostMapping("/reassign")
    public ClaimResponse reassign(@Valid @RequestBody ReassignClaimRequest req) {
        return response(service.reassign(req.sheet().trim(), req.user().trim(), req.id(), req.personId()));
    }

    @PostMapping("/bulk-open")
    public BulkOpenResponse bulkOpen(@Valid @RequestBody BulkOpenRequest req) {
        ClaimService.BulkOpened done = service.bulkOpen(req.sheet().trim(), req.user().trim(), req.ids());
        return new BulkOpenResponse(done.opened(), done.refused(),
                done.claims().stream().map(ClaimController::response).toList());
    }

    @PostMapping("/bulk-settle")
    public BulkSettleResponse bulkSettle(@Valid @RequestBody BulkOpenRequest req) {
        ClaimService.BulkSettled done = service.bulkSettle(req.sheet().trim(), req.user().trim(), req.ids());
        return new BulkSettleResponse(done.settled(), done.refused(),
                done.claims().stream().map(ClaimController::response).toList());
    }

    private ClaimResponse changeStatus(ClaimStatusRequest req, ClaimStatus next) {
        return response(service.changeStatus(req.sheet().trim(), req.user().trim(), req.id(), next));
    }
}
