package net.officefloor.hq.app.pool;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Listing the pools on a sheet, adding a pool and adding a member (a person or a pool) to one. */
@RestController
@RequestMapping("/api/pools")
public class PoolController {

    public record ListPoolsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record AddPoolRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 200) String name) {
    }

    public record AddMemberRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long pool, Long person, Long memberPool, @NotNull @Size(max = 20) String share,
            LocalDate from, LocalDate to) {
    }

    private final PoolService service;

    public PoolController(PoolService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<PoolService.PoolView> list(@Valid @RequestBody ListPoolsRequest req) {
        return service.list(req.sheet().trim());
    }

    @PostMapping("/add")
    public List<PoolService.PoolView> add(@Valid @RequestBody AddPoolRequest req) {
        String sheet = req.sheet().trim();
        service.add(sheet, req.user().trim(), req.name());
        return service.list(sheet);
    }

    @PostMapping("/members/add")
    public List<PoolService.PoolView> addMember(@Valid @RequestBody AddMemberRequest req) {
        String sheet = req.sheet().trim();
        if (req.memberPool() != null) {
            service.addPoolMember(sheet, req.user().trim(), req.pool(), req.memberPool(), req.share(),
                    req.from(), req.to());
        } else if (req.person() != null) {
            service.addMember(sheet, req.user().trim(), req.pool(), req.person(), req.share(), req.from(),
                    req.to());
        } else {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a member.");
        }
        return service.list(sheet);
    }
}
