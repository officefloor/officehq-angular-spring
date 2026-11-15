package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sheets/{sheetId}/pools")
@Validated
public class PoolController {

    /** A member as the screen shows it: the person's id, or the member pool's name, and the share
     *  with two decimals. */
    public record MemberView(Long personId, String pool, String share) {
        static MemberView of(PoolMember m, Map<Long, String> poolNames) {
            return new MemberView(m.getPersonId(),
                    m.getMemberPoolId() == null ? null : poolNames.get(m.getMemberPoolId()),
                    m.getShare().setScale(2).toPlainString());
        }
    }

    /** A pool as the screen shows it: its name and its members in the order they were added. */
    public record PoolView(String name, List<MemberView> members) {
        static PoolView of(PoolService.PoolWithMembers p, Map<Long, String> poolNames) {
            return new PoolView(p.pool().getName(),
                    p.members().stream().map(m -> MemberView.of(m, poolNames)).toList());
        }
    }

    /** A pool as the form submits it: a name (not blank). */
    public record PoolForm(@NotBlank @Size(max = 200) String name) {
    }

    /** A member as the pool's row submits it: the pool, a person or another pool of the sheet
     *  (exactly one), and a share percent with up to two decimals, more than 0.00 and at most
     *  100.00. */
    public record MemberForm(
            @NotBlank @Size(max = 200) String pool,
            Long personId,
            @Size(max = 200) String memberPool,
            @NotNull @Pattern(regexp = "\\s*\\d{1,3}(\\.\\d{1,2})?\\s*") String share) {
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    @GetMapping
    public List<PoolView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        List<PoolService.PoolWithMembers> all = service.list(sheetId);
        Map<Long, String> poolNames = all.stream()
                .collect(Collectors.toMap(p -> p.pool().getId(), p -> p.pool().getName()));
        return all.stream().map(p -> PoolView.of(p, poolNames)).toList();
    }

    /** Add a pool; resolves to the sheet's pools with it. */
    @PostMapping
    public List<PoolView> add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody PoolForm body) {
        service.add(sheetId, user.trim(), body.name().trim());
        return list(sheetId, user);
    }

    /** Add a member to a pool; resolves to the sheet's pools with it. */
    @PostMapping("/members")
    public List<PoolView> addMember(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MemberForm body) {
        boolean isPool = body.memberPool() != null && !body.memberPool().isBlank();
        if (isPool == (body.personId() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a person or a pool");
        }
        if (isPool) {
            service.addPoolMember(sheetId, user.trim(), body.pool().trim(), body.memberPool().trim(), share(body.share()));
        } else {
            service.addMember(sheetId, user.trim(), body.pool().trim(), body.personId(), share(body.share()));
        }
        return list(sheetId, user);
    }

    /** The share, refused (400) unless more than 0.00 and at most 100.00. */
    private static BigDecimal share(String text) {
        BigDecimal share = new BigDecimal(text.trim()).setScale(2);
        if (share.signum() <= 0 || share.compareTo(new BigDecimal("100.00")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A share is more than 0.00 and at most 100.00");
        }
        return share;
    }
}
