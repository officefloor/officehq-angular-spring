package net.officefloor.hq.app.grade;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.SignedInUser;
import net.officefloor.hq.app.person.ContactInUseException;
import net.officefloor.hq.app.person.NameInUseException;
import net.officefloor.hq.app.person.StalePersonException;
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
@RequestMapping("/api/sheets/{sheetId}/grades")
public class GradeController {

    /** A grade as the screen shows it: whether it is retired on the sheet. */
    public record GradeView(String grade, boolean retired) {
    }

    /** A grade to retire. */
    public record RetireRequest(@NotNull @Size(max = 10) String grade) {
    }

    /** A grade to add. */
    public record AddRequest(@NotNull @Size(max = 10) String grade) {
    }

    /** A grade to merge into another. */
    public record MergeRequest(@NotNull @Size(max = 10) String from, @NotNull @Size(max = 10) String into) {
    }

    /** How many people a merge gave the other grade. */
    public record MergeView(int merged) {
    }

    private final GradeService service;
    private final GradeMergeService merges;

    public GradeController(GradeService service, GradeMergeService merges) {
        this.service = service;
        this.merges = merges;
    }

    /** Every grade, in order, each with whether it is retired on the sheet. */
    @GetMapping
    public List<GradeView> list(@PathVariable @NotBlank @Size(max = 200) String sheetId) {
        Set<String> retired = service.retired(sheetId.trim());
        return service.grades(sheetId.trim()).stream().map(g -> new GradeView(g, retired.contains(g))).toList();
    }

    /** Add a grade on the sheet; the grades as they stand then. */
    @PostMapping
    public List<GradeView> add(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated AddRequest request) {
        String sheet = sheetId.trim();
        service.add(sheet, SignedInUser.decode(user), request.grade().trim());
        return list(sheet);
    }

    /** Retire a grade on the sheet; the grades as they stand then. */
    @PostMapping("/retire")
    public List<GradeView> retire(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated RetireRequest request) {
        String sheet = sheetId.trim();
        service.retire(sheet, SignedInUser.decode(user), request.grade().trim());
        return list(sheet);
    }

    /** Merge a grade into another; how many people were given the other grade. */
    @PostMapping("/merge")
    public MergeView merge(@PathVariable @NotBlank @Size(max = 200) String sheetId,
            @RequestHeader(SignedInUser.HEADER) @NotBlank String user, @RequestBody @Validated MergeRequest request) {
        return new MergeView(merges.merge(sheetId.trim(), SignedInUser.decode(user), request.from().trim(),
                request.into().trim()).size());
    }

    /** A grade that is not one, or is retired already, or one added that is a grade already or not a
     *  code, is refused; so is a merge refused, or one that
     *  could not give a person the grade as their editor would. */
    @ExceptionHandler({GradeRetiredException.class, GradeMergeRefusedException.class,
            DataIntegrityViolationException.class, StalePersonException.class, NameInUseException.class,
            ContactInUseException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public void refused() {
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void unreadable() {
    }
}
