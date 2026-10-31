package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.person.Person;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The crews on a sheet. The caller's signed-in name comes in the {@code X-User} header. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/crews")
public class CrewController {

    /** A person of a crew, as its row shows them. */
    public record PersonRef(long id, String name) {

        static PersonRef of(Person p) {
            return p == null ? null : new PersonRef(p.getId(), p.getName());
        }
    }

    /** A crew as the screen shows it: its lead (null once the lead has left the sheet) and its members. */
    public record CrewView(long id, String name, PersonRef lead, List<PersonRef> members) {

        public static CrewView of(CrewService.CrewWithMembers c) {
            return new CrewView(c.crew().getId(), c.crew().getName(), PersonRef.of(c.lead()),
                    c.members().stream().map(PersonRef::of).toList());
        }
    }

    /** A crew as the form submits it. */
    public record CrewForm(@NotNull @Size(max = 200) String name, @NotNull Long leadId) {
    }

    /** A member as the crew's row submits it. */
    public record MemberForm(@NotNull Long personId) {
    }

    private final CrewService crews;

    public CrewController(CrewService crews) {
        this.crews = crews;
    }

    @GetMapping
    public List<CrewView> list(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return crews.list(sheetId).stream().map(CrewView::of).toList();
    }

    @PostMapping
    public CrewView add(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody CrewForm request) {
        return CrewView.of(crews.add(sheetId, user, request.name(), request.leadId()));
    }

    @PostMapping("/{crewId}/members")
    public CrewView addMember(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long crewId,
            @RequestHeader("X-User") @NotBlank @Size(max = 100) String user,
            @Valid @RequestBody MemberForm request) {
        return CrewView.of(crews.addMember(sheetId, user, crewId, request.personId()));
    }
}
