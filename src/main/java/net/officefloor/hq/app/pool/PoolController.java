package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** The pools on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/pools")
@Validated
public class PoolController {

    /** A member of a pool: the person, their name and their share (two decimals). */
    public record MemberView(long personId, String name, String share) {
        static MemberView of(PoolService.Member m) {
            return new MemberView(m.personId(), m.name(), m.share().toPlainString());
        }
    }

    /** A pool: its id, name, members, total share (two decimals) and whether it is balanced. */
    public record PoolView(long id, String name, List<MemberView> members, String totalShare, boolean balanced) {
        static PoolView of(PoolService.Pooled p) {
            return new PoolView(p.pool().getId(), p.pool().getName(), p.members().stream().map(MemberView::of).toList(),
                    p.totalShare().toPlainString(), p.balanced());
        }
    }

    /** A pool asked: its name, trimmed, not blank. */
    public record PoolRequest(@NotNull @Size(max = 200) String name) {
    }

    /** A member asked: the person and their share as typed. */
    public record MemberRequest(@NotNull Long personId, @NotNull @Size(max = 20) String share) {
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    @GetMapping
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.pools(sheetId.trim()).stream().map(PoolView::of).toList();
    }

    @PostMapping
    public List<PoolView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid PoolRequest request) {
        String sheet = sheetId.trim();
        service.add(sheet, user.trim(), request.name().trim());
        return list(sheet);
    }

    @PostMapping("/{poolId}/members")
    public List<PoolView> addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long poolId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid MemberRequest request) {
        String sheet = sheetId.trim();
        service.addMember(sheet, user.trim(), poolId, request.personId(), request.share());
        return list(sheet);
    }
}
