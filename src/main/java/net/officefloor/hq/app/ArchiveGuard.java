package net.officefloor.hq.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refuses every change asked for on an archived sheet: any request on a sheet other than reading it
 * (a GET, or loading the sheet) is refused before it reaches its controller, so it writes nothing.
 * As for a viewer, the bulk changes are let through and answered with every selected claim refused
 * (see {@code BulkChangeService}).
 */
@Component
public class ArchiveGuard implements HandlerInterceptor {

    private final SheetRepository sheets;

    public ArchiveGuard(SheetRepository sheets) {
        this.sheets = sheets;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod())) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(ViewerGuard.SHEETS)) return true;
        String rest = path.substring(ViewerGuard.SHEETS.length());
        int slash = rest.indexOf('/');
        if (slash < 0 || ViewerGuard.LET_THROUGH.contains(rest.substring(slash + 1))) return true;
        String sheetId = URLDecoder.decode(rest.substring(0, slash), StandardCharsets.UTF_8).trim();
        if (sheets.isArchived(sheetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The sheet is archived: nothing on it can be changed.");
        }
        return true;
    }
}
