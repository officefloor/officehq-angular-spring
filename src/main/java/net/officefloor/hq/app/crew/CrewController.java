package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.person.Person;
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

    /** A person of a crew as the screen shows them: their id and name. */
    public record MemberView(long personId, String name) {
        static MemberView of(Person p) {
            return new MemberView(p.getId(), p.getName());
        }
    }

    /** A crew as the screen shows it: its lead (null when they have left the sheet) and its members. */
    public record CrewView(long id, String name, MemberView lead, List<MemberView> members) {
        static CrewView of(CrewService.Listed l) {
            return new CrewView(l.crew().getId(), l.crew().getName(),
                    l.lead() == null ? null : MemberView.of(l.lead()),
                    l.members().stream().map(MemberView::of).toList());
        }
    }

    /** The crew form: the required name (trimmed) and the lead. */
    public record CrewForm(@NotBlank @Size(max = 200) String name, @NotNull Long leadId) {
    }

    /** The member form: the person. */
    public record MemberForm(@NotNull Long personId) {
    }

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrewView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        return service.list(sheetId.trim()).stream().map(CrewView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CrewView add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CrewForm form) {
        return CrewView.of(service.add(sheetId.trim(), user.trim(), form.name(), form.leadId()));
    }

    @PostMapping("/{crewId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public CrewView addMember(@PathVariable @NotBlank @Size(max = 100) String sheetId, @PathVariable long crewId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MemberForm form) {
        return CrewView.of(service.addMember(sheetId.trim(), user.trim(), crewId, form.personId()));
    }
}
