package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** The crews on a sheet. The signed-in user's name travels in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/crews")
@Validated
public class CrewController {

    /** A person in a crew: their id and name. */
    public record MemberView(long personId, String name) {
        static MemberView of(CrewService.Member m) {
            return new MemberView(m.personId(), m.name());
        }
    }

    /** A crew: its id, name, lead (null once the lead has left the sheet) and members. */
    public record CrewView(long id, String name, MemberView lead, List<MemberView> members) {
        static CrewView of(CrewService.Crewed c) {
            return new CrewView(c.crew().getId(), c.crew().getName(),
                    c.lead() == null ? null : MemberView.of(c.lead()),
                    c.members().stream().map(MemberView::of).toList());
        }
    }

    /** A crew asked: its name, trimmed, not blank, and its lead. */
    public record CrewRequest(@NotNull @Size(max = 200) String name, @NotNull Long leadId) {
    }

    /** A member asked: the person. */
    public record MemberRequest(@NotNull Long personId) {
    }

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrewView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.crews(sheetId.trim()).stream().map(CrewView::of).toList();
    }

    @PostMapping
    public List<CrewView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid CrewRequest request) {
        String sheet = sheetId.trim();
        service.add(sheet, user.trim(), request.name().trim(), request.leadId());
        return list(sheet);
    }

    @PostMapping("/{crewId}/members")
    public List<CrewView> addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable long crewId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @RequestBody @Valid MemberRequest request) {
        String sheet = sheetId.trim();
        service.addMember(sheet, user.trim(), crewId, request.personId());
        return list(sheet);
    }
}
