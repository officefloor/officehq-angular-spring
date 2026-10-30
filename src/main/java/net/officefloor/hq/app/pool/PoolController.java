package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** The pools on a sheet and their members. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/pools")
@Validated
public class PoolController {

    /** A pool to add: a name (blank is refused). */
    public record PoolForm(@Size(max = 1000) String name) {
    }

    /**
     * A member to add to the named pool: a person of the sheet, or else another pool of the sheet by name, and the
     * share percent; the membership's from- and to-dates (yyyy-mm-dd), each optional.
     */
    public record MemberForm(@NotBlank @Size(max = 1000) String pool, Long personId,
            @Size(max = 1000) String memberPool, @Size(max = 100) String share, @Size(max = 100) String from,
            @Size(max = 100) String to) {
    }

    /**
     * A member of a pool: a person (pool null) or a pool (personId null); the share with two decimals, and so the
     * cut, null when the member has none; their cut at the end of each period ended by the sheet's today.
     */
    public record MemberView(Long personId, String pool, String name, String share, boolean retired, String cut,
            List<PeriodCutView> periodCuts) {
    }

    /** A member's cut at the end of the named period, null when they had none then. */
    public record PeriodCutView(String period, String cut) {
    }

    /** A pool on the sheet; its total share with two decimals; whether it is a crew's, its members the crew's. */
    public record PoolView(String name, List<MemberView> members, String total, boolean balanced, boolean crew) {

        static PoolView of(Pools.Shown p, List<Pools.Then> history) {
            return new PoolView(p.name(), p.members().stream()
                    .map(m -> new MemberView(m.personId(), m.pool(), m.name(), m.share().toPlainString(), m.retired(),
                            text(m.cut()), history.stream()
                                    .map(t -> new PeriodCutView(t.period(), text(t.cut(p.name(), m)))).toList()))
                    .toList(),
                    p.total().toPlainString(), p.balanced(), p.crew());
        }

        private static String text(BigDecimal figure) {
            return figure == null ? null : figure.toPlainString();
        }
    }

    public record Refusal(String message) {
    }

    private final Pools pools;

    public PoolController(Pools pools) {
        this.pools = pools;
    }

    @GetMapping
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        List<Pools.Shown> shown = pools.list(sheet);
        List<Pools.Then> history = pools.history(sheet);
        return shown.stream().map(p -> PoolView.of(p, history)).toList();
    }

    /** The pools on the sheet in the order they were added, each with its members in the order they were added. */
    @GetMapping(params = "order=added")
    public List<PoolView> listAsAdded(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        String sheet = sheetId.trim();
        List<Pools.Shown> shown = pools.list(sheet, true);
        List<Pools.Then> history = pools.history(sheet);
        return shown.stream().map(p -> PoolView.of(p, history)).toList();
    }

    /** Add a pool; answers with every pool on the sheet. */
    @PostMapping
    public List<PoolView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody PoolForm form) {
        String sheet = sheetId.trim();
        pools.add(sheet, user.trim(), form.name());
        return list(sheet);
    }

    /** Add a member to a pool; answers with every pool on the sheet. */
    @PostMapping("/members")
    public List<PoolView> addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody MemberForm form) {
        String sheet = sheetId.trim();
        LocalDate from = date(form.from());
        LocalDate to = date(form.to());
        if (form.personId() != null) {
            pools.addMember(sheet, user.trim(), form.pool(), form.personId(), form.share(), from, to);
        } else if (form.memberPool() != null && !form.memberPool().isBlank()) {
            pools.addPoolMember(sheet, user.trim(), form.pool(), form.memberPool(), form.share(), from, to);
        } else {
            throw new PoolRefused("Choose a person or a pool.");
        }
        return list(sheet);
    }

    /** A date typed as yyyy-mm-dd; null when blank. */
    private static LocalDate date(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new PoolRefused("Enter dates as yyyy-mm-dd.");
        }
    }

    @ExceptionHandler(PoolRefused.class)
    public ResponseEntity<Refusal> refused(PoolRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Refusal(e.getMessage()));
    }

    /** Another user added the same pool, or the same member, at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void taken() {
    }
}
