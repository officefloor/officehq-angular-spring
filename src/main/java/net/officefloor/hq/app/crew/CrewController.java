package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/sheets/{sheetId}/crews")
@Validated
public class CrewController {

    /** What the screen shows of a crew: its name, its lead and its members in the order they were added. */
    public record CrewView(long id, String name, long leadId, List<Long> memberIds) {
        static CrewView of(Crew crew) {
            return new CrewView(crew.getId(), crew.getName(), crew.getLeadId(), crew.getMemberIds());
        }
    }

    public record CrewForm(@Size(max = 200) String name, Long leadId) {
    }

    public record MemberForm(Long personId) {
    }

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    /** The sheet's crews in the order they were added. */
    @GetMapping
    public List<CrewView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(CrewView::of).toList();
    }

    /** Add a crew. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CrewView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid CrewForm form) {
        return CrewView.of(service.add(sheetId.trim(), SignedInUser.name(user), form.name(), form.leadId()));
    }

    /** Add a member to a crew: the crew as it is then. */
    @PostMapping("/{crewId}/members")
    public CrewView addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long crewId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Valid MemberForm form) {
        return CrewView.of(service.addMember(sheetId.trim(), SignedInUser.name(user), crewId, form.personId()));
    }
}
