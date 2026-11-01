package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/pools")
public class PoolController {

    /**
     * A member of a pool: the person or the pool (the other null), its name, its share (two decimals),
     * whether the person is retired, and its cut of the pot (two decimals, null: none).
     */
    public record MemberView(Long personId, Long poolId, String name, String share, boolean retired, String cut) {}

    /** A pool on the sheet, with its members, total share (two decimals) and whether it is balanced. */
    public record PoolView(Long id, String name, List<MemberView> members, String totalShare, boolean balanced) {
        static PoolView of(PoolService.PoolLine p) {
            return new PoolView(p.id(), p.name(),
                    p.members().stream().map(m -> new MemberView(m.personId(), m.poolId(), m.name(), m.share().toPlainString(), m.retired(),
                            m.cut() == null ? null : m.cut().toPlainString())).toList(),
                    p.totalShare().toPlainString(), p.balanced());
        }
    }

    /** A pool's name. */
    public record PoolRequest(@NotBlank @Size(max = 200) String name) {}

    /** A member to add: a person, or another pool, of the sheet (exactly one of them) and its share percent. */
    public record MemberRequest(Long personId, Long poolId, @NotBlank @Size(max = 20) String share) {
        @AssertTrue
        boolean isOneMember() {
            return (personId == null) != (poolId == null);
        }
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    @GetMapping
    public List<PoolView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.pools(sheetId).stream().map(PoolView::of).toList();
    }

    @PostMapping
    public List<PoolView> add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PoolRequest request) {
        service.add(sheetId, user, request.name());
        return list(sheetId, user);
    }

    @PostMapping("/{poolId}/members")
    public List<PoolView> addMember(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long poolId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid MemberRequest request) {
        if (request.poolId() != null) {
            service.addPoolMember(sheetId, user, poolId, request.poolId(), request.share());
        } else {
            service.addMember(sheetId, user, poolId, request.personId(), request.share());
        }
        return list(sheetId, user);
    }
}
