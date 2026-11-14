package net.officefloor.hq.app.pool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    /** A pool member as the screen shows it: a person, or another pool (memberPoolId); the share is a
     *  string with two decimals; from and to bound the membership, both included (null: no bound). */
    public record PoolMemberView(Long personId, Long memberPoolId, String share, LocalDate from, LocalDate to) {
        static PoolMemberView of(PoolMember member) {
            return new PoolMemberView(member.getPersonId(), member.getMemberPoolId(),
                    member.getShare().toPlainString(), member.getFrom(), member.getTo());
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

    /** A member: a person of the sheet, or another pool of the sheet (memberPoolId), and its share
     *  percent, with an optional from-date and to-date. */
    public record PoolMemberRequest(Long personId, Long memberPoolId, @NotNull BigDecimal share, LocalDate from,
            LocalDate to) {
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
                request.memberPoolId(), request.share(), request.from(), request.to()));
    }

    /** A blank or used name, a person already in the pool, a pool that may not be a member, a bad
     *  share, or a to-date before the from-date, is refused. */
    @ExceptionHandler({PoolRefusedException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
