package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
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

@RestController
@RequestMapping("/api/sheets/{sheetId}/pools")
@Validated
public class PoolController {

    /** A member as the screen shows them: the person, their share, whether they are retired, and
     *  their cut of the pot as text with two decimals (null when the pool shows no cut). */
    public record MemberView(long personId, String name, String share, boolean retired, String cut) {
        static MemberView of(PoolService.Member m, BigDecimal cut) {
            return new MemberView(m.person().getId(), m.person().getName(),
                    m.member().getShare().toPlainString(), m.person().isRetired(),
                    cut == null ? null : cut.toPlainString());
        }
    }

    /** A pool as the screen shows it: its members, their shares' total and whether it is 100.00. */
    public record PoolView(long id, String name, List<MemberView> members, String totalShare, boolean balanced) {
        static PoolView of(PoolService.Listed l) {
            List<BigDecimal> cuts = l.cuts();
            return new PoolView(l.pool().getId(), l.pool().getName(),
                    IntStream.range(0, l.members().size())
                            .mapToObj(i -> MemberView.of(l.members().get(i), cuts.isEmpty() ? null : cuts.get(i)))
                            .toList(),
                    l.totalShare().toPlainString(), l.balanced());
        }
    }

    /** The pool form: the required name (trimmed). */
    public record PoolForm(@NotBlank @Size(max = 200) String name) {
    }

    /** The member form: the person and their share as entered. */
    public record MemberForm(@NotNull Long personId, @NotBlank @Size(max = 20) String share) {
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    @GetMapping
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PoolView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PoolForm form) {
        return PoolView.of(service.add(sheetId.trim(), user.trim(), form.name()));
    }

    @PostMapping("/{poolId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long poolId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MemberForm form) {
        return PoolView.of(service.addMember(sheetId.trim(), user.trim(), poolId, form.personId(), form.share()));
    }
}
