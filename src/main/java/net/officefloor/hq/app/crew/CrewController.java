package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.session.CurrentUser;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/crews")
@Validated
public class CrewController {

    /** A crew as the screen shows it: its name, its lead's person id and its members' person ids
     *  in the order they were added. */
    public record CrewView(String name, Long leadId, List<Long> memberIds) {
        static CrewView of(CrewService.CrewWithMembers c) {
            return new CrewView(c.crew().getName(), c.crew().getLeadId(),
                    c.members().stream().map(CrewMember::getPersonId).toList());
        }
    }

    /** A crew as the form submits it: a name (not blank) and its lead's person id. */
    public record CrewForm(@NotBlank @Size(max = 200) String name, @NotNull Long leadId) {
    }

    /** A member as the crew's row submits it: the crew and the person's id. */
    public record MemberForm(@NotBlank @Size(max = 200) String crew, @NotNull Long personId) {
    }

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrewView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user) {
        return service.list(sheetId).stream().map(CrewView::of).toList();
    }

    /** Add a crew; resolves to the sheet's crews with it. */
    @PostMapping
    public List<CrewView> add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CrewForm body) {
        service.add(sheetId, user.trim(), body.name().trim(), body.leadId());
        return list(sheetId, user);
    }

    /** Add a member to a crew; resolves to the sheet's crews with it. */
    @PostMapping("/members")
    public List<CrewView> addMember(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "\\S+") String sheetId,
            @RequestHeader(CurrentUser.HEADER) @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MemberForm body) {
        service.addMember(sheetId, user.trim(), body.crew().trim(), body.personId());
        return list(sheetId, user);
    }
}
