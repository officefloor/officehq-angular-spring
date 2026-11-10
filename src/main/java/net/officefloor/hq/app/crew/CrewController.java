package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Listing the crews on a sheet, adding a crew with its lead and adding a member to one. */
@RestController
@RequestMapping("/api/crews")
public class CrewController {

    public record ListCrewsRequest(@NotBlank @Size(max = 100) String sheet) {
    }

    public record AddCrewRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull @Size(max = 200) String name, Long lead) {
    }

    public record AddMemberRequest(@NotBlank @Size(max = 100) String sheet, @NotBlank @Size(max = 100) String user,
            @NotNull Long crew, Long person) {
    }

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    @PostMapping("/list")
    public List<CrewService.CrewView> list(@Valid @RequestBody ListCrewsRequest req) {
        return service.list(req.sheet().trim());
    }

    @PostMapping("/add")
    public List<CrewService.CrewView> add(@Valid @RequestBody AddCrewRequest req) {
        String sheet = req.sheet().trim();
        service.add(sheet, req.user().trim(), req.name(), req.lead());
        return service.list(sheet);
    }

    @PostMapping("/members/add")
    public List<CrewService.CrewView> addMember(@Valid @RequestBody AddMemberRequest req) {
        String sheet = req.sheet().trim();
        service.addMember(sheet, req.user().trim(), req.crew(), req.person());
        return service.list(sheet);
    }
}
