package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.history.AsOfGuard;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** A member of a pool: the person (or the pool, the other null), its name, its share and its cut (two
     * decimals; the cut null when none). */
    public record MemberView(Long personId, Long poolId, String name, String share, String cut) {
        static MemberView of(PoolService.Member m) {
            return new MemberView(m.personId(), m.poolId(), m.name(), m.share().toPlainString(),
                    m.cut() == null ? null : m.cut().toPlainString());
        }
    }

    /** A pool: its id (a crew's id, and the crew id, for a crew; the crew id null for a pool), name, members,
     * total share (two decimals) and whether it is balanced. */
    public record PoolView(long id, Long crewId, String name, List<MemberView> members, String totalShare,
            boolean balanced) {
        static PoolView of(PoolService.Pooled p) {
            return new PoolView(p.id(), p.crewId(), p.name(), p.members().stream().map(MemberView::of).toList(),
                    p.totalShare().toPlainString(), p.balanced());
        }
    }

    /** A pool asked: its name, trimmed, not blank. */
    public record PoolRequest(@NotNull @Size(max = 200) String name) {
    }

    /** A member asked: the person or the pool (exactly one), the share as typed and the optional from- and
     * to-dates (yyyy-mm-dd, blank or absent for none). */
    public record MemberRequest(Long personId, Long poolId, @NotNull @Size(max = 20) String share,
            @Size(max = 20) String from, @Size(max = 20) String to) {
        @AssertTrue
        public boolean isOneMember() {
            return (personId == null) != (poolId == null);
        }
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    /** The sheet's pools; with an as-of date, worked out with that date standing in for the sheet's today. */
    @GetMapping
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(value = AsOfGuard.HEADER, required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.pools(sheetId.trim(), asOf).stream().map(PoolView::of).toList();
    }

    private List<PoolView> list(String sheetId) {
        return list(sheetId, null);
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
        if (request.poolId() != null) {
            service.addPoolMember(sheet, user.trim(), poolId, request.poolId(), request.share(), request.from(),
                    request.to());
        } else {
            service.addMember(sheet, user.trim(), poolId, request.personId(), request.share(), request.from(),
                    request.to());
        }
        return list(sheet);
    }
}
