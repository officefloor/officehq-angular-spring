package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    /** A member to add to the named pool: a person of the sheet and their share percent. */
    public record MemberForm(@NotBlank @Size(max = 1000) String pool, @NotNull Long personId,
            @Size(max = 100) String share) {
    }

    /** A member of a pool; the share with two decimals, and so the cut, null when the member has none. */
    public record MemberView(long personId, String name, String share, boolean retired, String cut) {
    }

    /** A pool on the sheet; its total share with two decimals. */
    public record PoolView(String name, List<MemberView> members, String total, boolean balanced) {

        static PoolView of(Pools.Shown p) {
            return new PoolView(p.name(), p.members().stream()
                    .map(m -> new MemberView(m.personId(), m.name(), m.share().toPlainString(), m.retired(),
                            m.cut() == null ? null : m.cut().toPlainString())).toList(),
                    p.total().toPlainString(), p.balanced());
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
        return pools.list(sheetId.trim()).stream().map(PoolView::of).toList();
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
        pools.addMember(sheet, user.trim(), form.pool(), form.personId(), form.share());
        return list(sheet);
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
