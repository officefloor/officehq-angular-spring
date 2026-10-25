package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
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

    /** A member of a pool as the screen shows it; the share always has two decimals. */
    public record MemberView(long personId, String share) {
        static MemberView of(PoolMember member) {
            return new MemberView(member.getPersonId(), member.getShare().toPlainString());
        }
    }

    /** What the screen shows of a pool: its name and its members in the order they were added. */
    public record PoolView(long id, String name, List<MemberView> members) {
        static PoolView of(Pool pool) {
            return new PoolView(pool.getId(), pool.getName(), pool.getMembers().stream().map(MemberView::of).toList());
        }
    }

    public record PoolForm(@Size(max = 200) String name) {
    }

    /** A member to add: a person of the sheet, and their share as typed. */
    public record MemberForm(@NotNull Long personId, @Size(max = 30) String share) {
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    /** The sheet's pools in the order they were added. */
    @GetMapping
    public List<PoolView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(PoolView::of).toList();
    }

    /** Add a pool. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PoolView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid PoolForm form) {
        return PoolView.of(service.add(sheetId.trim(), SignedInUser.name(user), form.name()));
    }

    /** Add a member to a pool: the pool as it is then. */
    @PostMapping("/{poolId}/members")
    public PoolView addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long poolId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid MemberForm form) {
        return PoolView.of(service.addMember(sheetId.trim(), SignedInUser.name(user), poolId, form.personId(),
                form.share()));
    }
}
