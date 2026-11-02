package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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
     * whether the person is retired, whether its dates include the sheet's today, its cut of the pot (two decimals, null: none),
     * and its cut as it stood at the end of each period ended by the sheet's today.
     */
    public record MemberView(Long personId, Long poolId, String name, String share, boolean retired,
            boolean active, String cut, List<PeriodCut> periodCuts) {}

    /** A member's cut as it stood at the end of a period (two decimals, null: none). */
    public record PeriodCut(String period, String cut) {}

    /**
     * A pool on the sheet, with its members, total share (two decimals) and whether it is balanced; a crew's
     * pool has no id and carries the crew's id.
     */
    public record PoolView(Long id, Long crewId, String name, List<MemberView> members, String totalShare, boolean balanced) {
        static PoolView of(PoolService.PoolLine p, List<PoolCutHistory.PeriodPools> history) {
            return new PoolView(p.id(), p.crewId(), p.name(),
                    p.members().stream().map(m -> new MemberView(m.personId(), m.poolId(), m.name(), m.share().toPlainString(), m.retired(),
                            m.active(), text(m.cut()),
                            history.stream().map(h -> new PeriodCut(h.period(), text(cutThen(p, m, h.pools())))).toList())).toList(),
                    p.totalShare().toPlainString(), p.balanced());
        }

        /** The member's cut in the pool as the pools then stood (null: none, or not there then). */
        private static BigDecimal cutThen(PoolService.PoolLine p, PoolService.Member m, List<PoolService.PoolLine> then) {
            return then.stream()
                    .filter(t -> Objects.equals(t.id(), p.id()) && Objects.equals(t.crewId(), p.crewId()))
                    .flatMap(t -> t.members().stream())
                    .filter(o -> Objects.equals(o.personId(), m.personId()) && Objects.equals(o.poolId(), m.poolId()))
                    .findFirst().map(PoolService.Member::cut).orElse(null);
        }

        private static String text(BigDecimal value) {
            return value == null ? null : value.toPlainString();
        }
    }

    /** A pool's name. */
    public record PoolRequest(@NotBlank @Size(max = 200) String name) {}

    /**
     * A member to add: a person, or another pool, of the sheet (exactly one of them), its share percent, and
     * the optional from-date and to-date of the membership.
     */
    public record MemberRequest(Long personId, Long poolId, @NotBlank @Size(max = 20) String share, LocalDate from,
            LocalDate to) {
        @AssertTrue
        boolean isOneMember() {
            return (personId == null) != (poolId == null);
        }
    }

    private final PoolService service;
    private final PoolCutHistory history;

    public PoolController(PoolService service, PoolCutHistory history) {
        this.service = service;
        this.history = history;
    }

    @GetMapping
    public List<PoolView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        List<PoolCutHistory.PeriodPools> then = history.history(sheetId);
        return service.pools(sheetId).stream().map(p -> PoolView.of(p, then)).toList();
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
            service.addPoolMember(sheetId, user, poolId, request.poolId(), request.share(), request.from(), request.to());
        } else {
            service.addMember(sheetId, user, poolId, request.personId(), request.share(), request.from(), request.to());
        }
        return list(sheetId, user);
    }
}
