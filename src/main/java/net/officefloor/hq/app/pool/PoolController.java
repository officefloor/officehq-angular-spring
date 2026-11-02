package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    /** A member as the screen shows them: the person, their share, and whether they are retired. */
    public record MemberView(long personId, String name, String share, boolean retired) {
        static MemberView of(PoolService.Member m) {
            return new MemberView(m.person().getId(), m.person().getName(),
                    m.member().getShare().toPlainString(), m.person().isRetired());
        }
    }

    /** A pool as the screen shows it: its members, their shares' total and whether it is 100.00. */
    public record PoolView(long id, String name, List<MemberView> members, String totalShare, boolean balanced) {
        static PoolView of(PoolService.Listed l) {
            return new PoolView(l.pool().getId(), l.pool().getName(),
                    l.members().stream().map(MemberView::of).toList(),
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
