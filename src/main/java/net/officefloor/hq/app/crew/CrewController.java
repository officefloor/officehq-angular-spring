package net.officefloor.hq.app.crew;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedIn;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    /** What the screen is told about a crew: its name, its lead and its members in the order they were added. */
    public record CrewView(long id, String name, long leadId, String lead, List<MemberView> members) {

        static CrewView of(CrewService.CrewWithMembers crew) {
            return new CrewView(crew.crew().getId(), crew.crew().getName(), crew.crew().getLeadId(), crew.lead(),
                    crew.members().stream().map(MemberView::of).toList());
        }
    }

    /** A crew's member: the person and their name. */
    public record MemberView(long personId, String name) {

        static MemberView of(CrewService.Member member) {
            return new MemberView(member.member().getPersonId(), member.name());
        }
    }

    /** A crew as entered on the form: a name, trimmed by the service, and its lead. */
    public record CrewForm(@NotNull @Size(max = 100) String name, @NotNull Long leadId) {
    }

    /** A member as chosen on the crew's row. */
    public record MemberForm(@NotNull Long personId) {
    }

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    /** The sheet's crews in the order they were added. */
    @GetMapping
    public List<CrewView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        return service.list(sheetId).stream().map(CrewView::of).toList();
    }

    /** Add a crew to the sheet. */
    @PostMapping
    public CrewView add(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid CrewForm form) {
        return CrewView.of(service.add(sheetId, SignedIn.name(user), form.name(), form.leadId()));
    }

    /** Add a member to a crew: the crew as it is then. */
    @PostMapping("/{crewId}/members")
    public CrewView addMember(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable long crewId,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user,
            @RequestBody @Valid MemberForm form) {
        return CrewView.of(service.addMember(sheetId, SignedIn.name(user), crewId, form.personId()));
    }

    /** Another user added the same name or member between the check and the write: the unique index refuses it. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void taken() {
    }
}
