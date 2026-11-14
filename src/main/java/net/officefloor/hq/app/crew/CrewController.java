package net.officefloor.hq.app.crew;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
@Validated
@RequestMapping("/api/sheets/{sheetId}/crews")
public class CrewController {

    /** A crew as the screen shows it: its lead and its members' person ids, in the order they were added. */
    public record CrewView(Long id, String name, Long leadId, List<Long> memberIds) {
        static CrewView of(CrewService.CrewWithMembers crew) {
            return new CrewView(crew.crew().getId(), crew.crew().getName(), crew.crew().getLeadId(),
                    crew.members().stream().map(CrewMember::getPersonId).toList());
        }
    }

    /** A crew: its name, trimmed as it is added, and its lead, a person of the sheet. */
    public record CrewRequest(@NotBlank @Size(max = 255) String name, @NotNull Long leadId) {
    }

    /** A member: a person of the sheet. */
    public record CrewMemberRequest(@NotNull Long personId) {
    }

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    /** The sheet's crews, in the order they were added. */
    @GetMapping
    public List<CrewView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        return service.list(sheetId.trim()).stream().map(CrewView::of).toList();
    }

    /** Add a crew to the sheet. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CrewView add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated CrewRequest request) {
        return CrewView.of(service.add(sheetId.trim(), SignedInUser.decode(user), request.name(), request.leadId()));
    }

    /** Add a member to one of the sheet's crews. */
    @PostMapping("/{crewId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public CrewView addMember(@PathVariable @NotBlank @Size(max = 200) String sheetId, @PathVariable Long crewId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user,
            @RequestBody @Validated CrewMemberRequest request) {
        return CrewView.of(service.addMember(sheetId.trim(), SignedInUser.decode(user), crewId, request.personId()));
    }

    /** A blank or used name, a person not of the sheet, or a member who leads the crew or is in it
     *  already, is refused. */
    @ExceptionHandler({CrewRefusedException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
