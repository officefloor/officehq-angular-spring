package net.officefloor.hq.app.pool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/pools")
public class PoolController {

    /** A pool member as the screen shows it; the share is a string with two decimals. */
    public record PoolMemberView(Long personId, String share) {
        static PoolMemberView of(PoolMember member) {
            return new PoolMemberView(member.getPersonId(), member.getShare().toPlainString());
        }
    }

    /** A pool as the screen shows it, with its members in the order they were added. */
    public record PoolView(Long id, String name, List<PoolMemberView> members) {
        static PoolView of(PoolService.PoolWithMembers pool) {
            return new PoolView(pool.pool().getId(), pool.pool().getName(),
                    pool.members().stream().map(PoolMemberView::of).toList());
        }
    }

    /** A pool: its name, trimmed as it is added. */
    public record PoolRequest(@NotBlank @Size(max = 255) String name) {
    }

    /** A member: a person of the sheet and their share percent. */
    public record PoolMemberRequest(@NotNull Long personId, @NotNull BigDecimal share) {
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    /** The sheet's pools, in the order they were added. */
    @GetMapping
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PoolView::of).toList();
    }

    /** Add a pool to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated PoolRequest request) {
        return PoolView.of(service.add(sheetId.trim(), SignedInUser.decode(user), request.name()));
    }

    /** Add a member to one of the sheet's pools. */
    @PostMapping("/{poolId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView addMember(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long poolId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated PoolMemberRequest request) {
        return PoolView.of(service.addMember(sheetId.trim(), SignedInUser.decode(user), poolId, request.personId(),
                request.share()));
    }

    /** A blank or used name, a person already in the pool, or a bad share, is refused. */
    @ExceptionHandler({PoolRefusedException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
