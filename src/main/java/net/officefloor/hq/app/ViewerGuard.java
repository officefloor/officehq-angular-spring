package net.officefloor.hq.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refuses every change a viewer of a sheet asks for: any request on a sheet other than reading it
 * (a GET, or loading the sheet) is refused before it reaches its controller, so it writes nothing.
 * The bulk changes are let through: a viewer's bulk change is answered with every selected claim
 * refused (see {@code BulkChangeService}).
 */
@Component
public class ViewerGuard implements HandlerInterceptor {

    /** The path of a sheet's requests; the sheet id is the segment after it. */
    public static final String SHEETS = "/api/sheets/";

    static final Set<String> LET_THROUGH = Set.of("load", "claims/open", "claims/settle");

    private final SettingService settings;

    public ViewerGuard(SettingService settings) {
        this.settings = settings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod())) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String header = request.getHeader(SignedInUser.HEADER);
        if (!path.startsWith(SHEETS) || header == null || header.isBlank()) return true;
        String rest = path.substring(SHEETS.length());
        int slash = rest.indexOf('/');
        if (slash < 0 || LET_THROUGH.contains(rest.substring(slash + 1))) return true;
        String sheetId = URLDecoder.decode(rest.substring(0, slash), StandardCharsets.UTF_8).trim();
        if (settings.roleOf(sheetId, SignedInUser.name(header)) == Role.VIEWER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A viewer cannot change anything.");
        }
        return true;
    }
}
