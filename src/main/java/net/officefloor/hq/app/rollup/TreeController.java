package net.officefloor.hq.app.rollup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The people of the other sheets of a sheet's whole tree, which the screen adds after its own for the tree export. */
@RestController
@Validated
@RequestMapping("/api/sheets/{sheetId}/tree")
public class TreeController {

    /** One person of another sheet of the tree: the sheet, name, grade today and due-total (null for n/a). */
    public record TreePersonView(String sheet, String name, String grade, String dueTotal) {
        static TreePersonView of(RollupService.TreePerson p) {
            return new TreePersonView(p.sheet(), p.name(), p.grade(),
                    p.dueTotal() == null ? null : p.dueTotal().toPlainString());
        }
    }

    private final RollupService service;

    public TreeController(RollupService service) {
        this.service = service;
    }

    @GetMapping("/people")
    public List<TreePersonView> people(
            @PathVariable @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_.-]+") String sheetId) {
        return service.treePeople(sheetId).stream().map(TreePersonView::of).toList();
    }
}
