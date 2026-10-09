package net.officefloor.hq.app.project;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The projects being done for a client. */
@RestController
@RequestMapping("/api/clients/{clientId}/projects")
public class ClientProjectController {

    private final ProjectService service;

    public ClientProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProjectResponse> list(@PathVariable Long clientId,
            @RequestParam(defaultValue = "false") boolean includeAll) {
        return service.listForClient(clientId, includeAll);
    }
}
