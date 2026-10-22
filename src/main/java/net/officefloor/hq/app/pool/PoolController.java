package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets/{sheetId}/pools")
@Validated
public class PoolController {

    /** What the screen is told about a pool: its name and its members in the order they were added. */
    public record PoolView(long id, String name, List<MemberView> members) {

        static PoolView of(PoolService.PoolWithMembers pool) {
            return new PoolView(pool.pool().getId(), pool.pool().getName(),
                    pool.members().stream().map(MemberView::of).toList());
        }
    }

    /**
     * A pool's member: the person or the member pool (the other null), its name, and its share percent with
     * two decimals.
     */
    public record MemberView(Long personId, Long poolId, String name, String share) {

        static MemberView of(PoolService.Member member) {
            return new MemberView(member.member().getPersonId(), member.member().getMemberPoolId(), member.name(),
                    member.member().getShare().toPlainString());
        }
    }

    /** A pool as entered on the form: a name, trimmed by the service. */
    public record PoolForm(@NotNull @Size(max = 100) String name) {
    }

    /**
     * A member as entered on the pool's row: a person or another pool (exactly one of them) and a share
     * percent with up to two decimals.
     */
    public record MemberForm(Long personId, Long poolId, @NotNull @Pattern(regexp = "\\d{1,3}(\\.\\d{1,2})?") String share) {
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    /** The sheet's pools in the order they were added. */
    @GetMapping
    public List<PoolView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(PoolView::of).toList();
    }

    /** Add a pool to the sheet. */
    @PostMapping
    public PoolView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid PoolForm form) {
        return PoolView.of(service.add(sheetId, SignedIn.name(user), form.name()));
    }

    /** Add a member to a pool: the pool as it is then. */
    @PostMapping("/{poolId}/members")
    public PoolView addMember(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long poolId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid MemberForm form) {
        if ((form.personId() == null) == (form.poolId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a person or a pool.");
        }
        if (form.poolId() != null) {
            return PoolView.of(service.addPoolMember(sheetId, SignedIn.name(user), poolId, form.poolId(),
                    new BigDecimal(form.share())));
        }
        return PoolView.of(service.addMember(sheetId, SignedIn.name(user), poolId, form.personId(),
                new BigDecimal(form.share())));
    }

    /** Another user added the same name or member between the check and the write: the unique index refuses it. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void taken() {
    }
}
