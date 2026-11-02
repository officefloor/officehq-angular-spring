package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets/{sheetId}/pools")
@Validated
public class PoolController {

    /** A member as the screen shows them: the person (null for a pool member) or the pool (null for
     *  a person) and its name, their share, whether they are retired, their cut of the pot as text
     *  with two decimals (null when the pool shows no cut, or the member is out of force), the days
     *  the membership runs (null: open-ended) and whether they include the sheet's today. */
    public record MemberView(Long personId, Long poolId, String name, String share, boolean retired, String cut,
            LocalDate from, LocalDate to, boolean inForce) {
        static MemberView of(PoolService.Member m, BigDecimal cut) {
            return new MemberView(m.person() == null ? null : m.person().getId(),
                    m.pool() == null ? null : m.pool().getId(),
                    m.person() != null ? m.person().getName() : m.pool().getName(),
                    m.member().getShare().toPlainString(), m.retired(),
                    cut == null ? null : cut.toPlainString(),
                    m.member().getFromDate(), m.member().getToDate(), m.inForce());
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

    /** The member form: the person, or the pool, their share as entered, and the optional days the
     *  membership runs from and to. */
    public record MemberForm(Long personId, Long poolId, @NotBlank @Size(max = 20) String share,
            LocalDate from, LocalDate to) {
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
        if ((form.personId() == null) == (form.poolId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a person or a pool");
        }
        return PoolView.of(form.personId() != null
                ? service.addMember(sheetId.trim(), user.trim(), poolId, form.personId(), form.share(), form.from(), form.to())
                : service.addPoolMember(sheetId.trim(), user.trim(), poolId, form.poolId(), form.share(), form.from(), form.to()));
    }
}
