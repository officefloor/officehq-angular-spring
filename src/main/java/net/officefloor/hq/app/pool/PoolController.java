package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The pools of a sheet and their members. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/pools")
@Validated
public class PoolController {

    /** A member as shown: the person's id and their share (two decimals). */
    public record PoolMemberView(Long personId, String share) {

        static PoolMemberView of(PoolMember m) {
            return new PoolMemberView(m.getPersonId(), m.getShare().toPlainString());
        }
    }

    /** A pool as shown, with its members in the order they were added. */
    public record PoolView(Long id, String name, List<PoolMemberView> members) {

        static PoolView of(Pool p) {
            return new PoolView(p.getId(), p.getName(), p.getMembers().stream().map(PoolMemberView::of).toList());
        }
    }

    /** The pool's name: surrounding spaces are removed, and it must not then be blank. */
    public record PoolRequest(@NotNull @NotBlank @Size(max = 255) String name) {
    }

    /** A member to add: the person and their share, more than 0.00 and at most 100.00, with at most two decimals. */
    public record PoolMemberRequest(@NotNull Long personId,
            @NotNull @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String share) {
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    /** The sheet's pools, in the order they were added. */
    @GetMapping
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId.trim()).stream().map(PoolView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PoolRequest request) {
        return PoolView.of(service.add(sheetId.trim(), user.trim(), request.name().trim()));
    }

    @PostMapping("/{poolId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long poolId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PoolMemberRequest request) {
        return PoolView.of(service.addMember(sheetId.trim(), user.trim(), poolId, request.personId(),
                new BigDecimal(request.share().trim())));
    }
}
