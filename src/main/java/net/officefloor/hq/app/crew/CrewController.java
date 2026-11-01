package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/crews")
public class CrewController {

    /** A person in a crew: their id and name. */
    public record MemberView(Long personId, String name) {}

    /** A crew on the sheet, with its lead (null: none on the sheet) and its members in the order added. */
    public record CrewView(Long id, String name, MemberView lead, List<MemberView> members) {
        static CrewView of(CrewService.CrewLine c) {
            return new CrewView(c.id(), c.name(),
                    c.lead() == null ? null : new MemberView(c.lead().personId(), c.lead().name()),
                    c.members().stream().map(m -> new MemberView(m.personId(), m.name())).toList());
        }
    }

    /** A crew's name and its lead. */
    public record CrewRequest(@NotBlank @Size(max = 200) String name, @NotNull Long leadId) {}

    /** A person to add to a crew. */
    public record MemberRequest(@NotNull Long personId) {}

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrewView> list(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user) {
        return service.crews(sheetId).stream().map(CrewView::of).toList();
    }

    @PostMapping
    public List<CrewView> add(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CrewRequest request) {
        service.add(sheetId, user, request.name(), request.leadId());
        return list(sheetId, user);
    }

    @PostMapping("/{crewId}/members")
    public List<CrewView> addMember(
            @PathVariable @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable Long crewId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid MemberRequest request) {
        service.addMember(sheetId, user, crewId, request.personId());
        return list(sheetId, user);
    }
}
