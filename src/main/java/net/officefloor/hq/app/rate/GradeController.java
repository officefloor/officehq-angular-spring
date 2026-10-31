package net.officefloor.hq.app.rate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.person.GradeMergeService;
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

/** The grades of a sheet and whether each is retired. The signed-in user's name comes in the {@code X-User} header. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/grades")
@Validated
public class GradeController {

    public record GradeView(String grade, boolean retired) {
    }

    /** A grade to add. */
    public record AddForm(@NotNull @Size(max = 10) String grade) {
    }

    /** A merge of one grade into another. */
    public record MergeForm(@NotBlank @Size(max = 10) String from, @NotBlank @Size(max = 10) String into) {
    }

    private final GradeService service;
    private final GradeMergeService merges;

    public GradeController(GradeService service, GradeMergeService merges) {
        this.service = service;
        this.merges = merges;
    }

    @GetMapping
    public List<GradeView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        Set<String> retired = service.retired(sheetId.trim());
        return service.grades(sheetId.trim()).stream().map(g -> new GradeView(g, retired.contains(g))).toList();
    }

    /** Add a grade to the sheet; answers the sheet's grades with it. */
    @PostMapping
    public List<GradeView> add(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody AddForm form) {
        service.add(sheetId.trim(), user.trim(), form.grade().trim());
        return list(sheetId);
    }

    @PostMapping("/{grade}/retire")
    public GradeView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 10) String grade, @RequestHeader("X-User") @NotBlank String user) {
        service.retire(sheetId.trim(), user.trim(), grade.trim());
        return new GradeView(grade.trim(), true);
    }

    /** Merge a grade into another; answers how many people were moved onto it. */
    @PostMapping("/merge")
    public GradeMergeService.Result merge(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestHeader("X-User") @NotBlank String user, @Valid @RequestBody MergeForm form) {
        return merges.merge(sheetId.trim(), user.trim(), form.from().trim(), form.into().trim());
    }

    /** Another user retired or added the same grade at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void taken() {
    }
}
