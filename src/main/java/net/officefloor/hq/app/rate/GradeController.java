package net.officefloor.hq.app.rate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final GradeService service;

    public GradeController(GradeService service) {
        this.service = service;
    }

    @GetMapping
    public List<GradeView> list(@PathVariable @NotBlank @Size(max = 100) String sheetId) {
        Set<String> retired = service.retired(sheetId.trim());
        return GradeService.GRADES.stream().map(g -> new GradeView(g, retired.contains(g))).toList();
    }

    @PostMapping("/{grade}/retire")
    public GradeView retire(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable @NotBlank @Size(max = 10) String grade, @RequestHeader("X-User") @NotBlank String user) {
        service.retire(sheetId.trim(), user.trim(), grade.trim());
        return new GradeView(grade.trim(), true);
    }

    /** Another user retired the same grade at the same moment: this one is refused. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void taken() {
    }
}
