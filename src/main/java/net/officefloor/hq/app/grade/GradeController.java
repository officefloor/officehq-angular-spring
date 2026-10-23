package net.officefloor.hq.app.grade;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.SignedIn;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sheets/{sheetId}/grades")
@Validated
public class GradeController {

    /** What the screen is told about a grade: whether it is retired on the sheet. */
    public record GradeView(String grade, boolean retired) {
    }

    private final GradeService service;

    public GradeController(GradeService service) {
        this.service = service;
    }

    /** The sheet's grades, in order. */
    @GetMapping
    public List<GradeView> list(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId) {
        Set<String> retired = service.retired(sheetId);
        return GradeService.GRADES.stream().map((g) -> new GradeView(g, retired.contains(g))).toList();
    }

    /** Retire a grade on the sheet: the sheet's grades as they are now. */
    @PostMapping("/{grade}/retire")
    public List<GradeView> retire(
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String sheetId,
            @PathVariable @Pattern(regexp = "[ABC]") String grade,
            @RequestHeader(SignedIn.HEADER) @NotBlank @Size(max = 200) String user) {
        service.retire(sheetId, SignedIn.name(user), grade);
        return list(sheetId);
    }
}
