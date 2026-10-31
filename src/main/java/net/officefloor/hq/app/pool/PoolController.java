package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The pools on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/pools")
public class PoolController {

    /** A member as the pool's row shows it. */
    public record MemberView(long personId, String name, String share, boolean retired) {

        public static MemberView of(PoolService.Member m) {
            return new MemberView(m.person().getId(), m.person().getName(), m.share().toPlainString(),
                    m.person().isRetired());
        }
    }

    /** A pool as the screen shows it: its members, their total share and whether that is exactly 100.00. */
    public record PoolView(long id, String name, List<MemberView> members, String totalShare, boolean balanced) {

        public static PoolView of(PoolService.PoolWithMembers p) {
            return new PoolView(p.pool().getId(), p.pool().getName(), p.members().stream().map(MemberView::of).toList(),
                    p.totalShare().toPlainString(), p.balanced());
        }
    }

    /** A pool as the form submits it. */
    public record PoolForm(@NotNull @Size(max = 200) String name) {
    }

    /** A member as the pool's row submits it. */
    public record MemberForm(@NotNull Long personId, @NotNull BigDecimal share) {
    }

    private final PoolService pools;

    public PoolController(PoolService pools) {
        this.pools = pools;
    }

    @GetMapping
    public List<PoolView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return pools.list(sheetId).stream().map(PoolView::of).toList();
    }

    @PostMapping
    public PoolView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PoolForm request) {
        return PoolView.of(new PoolService.PoolWithMembers(pools.add(sheetId, user, request.name()), List.of()));
    }

    @PostMapping("/{poolId}/members")
    public PoolView addMember(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long poolId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MemberForm request) {
        return PoolView.of(pools.addMember(sheetId, user, poolId, request.personId(), request.share()));
    }
}
