package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
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

    /** A member as the pool's row shows it: a person (personId) or another pool (memberPoolId, its name as the
     *  name), with the membership's from-date and to-date (null: none); cut is its cut of the pool's pot, null when
     *  the pool shows none or the membership's dates do not include the sheet's today. */
    public record MemberView(Long personId, Long memberPoolId, String name, String share, boolean retired,
            String cut, LocalDate from, LocalDate to) {

        public static MemberView of(PoolService.Member m, BigDecimal cut) {
            String shown = cut == null || !m.inForce() ? null : cut.toPlainString();
            if (m.pool() != null) {
                return new MemberView(null, m.pool().getId(), m.pool().getName(), m.share().toPlainString(), false,
                        shown, m.from(), m.to());
            }
            return new MemberView(m.person().getId(), null, m.person().getName(), m.share().toPlainString(),
                    m.person().isRetired(), shown, m.from(), m.to());
        }
    }

    /** A pool as the screen shows it: its members, their total share and whether that is exactly 100.00. */
    public record PoolView(long id, String name, List<MemberView> members, String totalShare, boolean balanced) {

        public static PoolView of(PoolService.PoolWithMembers p) {
            List<BigDecimal> cuts = p.cuts();
            return new PoolView(p.pool().getId(), p.pool().getName(),
                    IntStream.range(0, p.members().size())
                            .mapToObj(i -> MemberView.of(p.members().get(i), cuts == null ? null : cuts.get(i))).toList(),
                    p.totalShare().toPlainString(), p.balanced());
        }
    }

    /** A pool as the form submits it. */
    public record PoolForm(@NotNull @Size(max = 200) String name) {
    }

    /** A member as the pool's row submits it: a person or another pool of the sheet, exactly one given, with the
     *  membership's optional from-date and to-date. */
    public record MemberForm(Long personId, Long memberPoolId, @NotNull BigDecimal share, LocalDate from,
            LocalDate to) {
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
        return PoolView.of(new PoolService.PoolWithMembers(pools.add(sheetId, user, request.name()), List.of(), null));
    }

    @PostMapping("/{poolId}/members")
    public PoolView addMember(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long poolId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MemberForm request) {
        return PoolView.of(pools.addMember(sheetId, user, poolId, request.personId(), request.memberPoolId(),
                request.share(), request.from(), request.to()));
    }
}
