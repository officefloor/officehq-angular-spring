package net.officefloor.hq.app.setting;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.SignedInUser;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A viewer sees every screen and figure and can change nothing: every request that would change a
 * sheet is refused (403) before it reaches its controller, so it writes nothing to the store or the
 * audit. Loading a sheet changes nothing and is let through; so are the bulk actions, which a
 * viewer takes with nothing done (every selected item refused, see the bulk services).
 */
@Component
public class ViewerGuard implements HandlerInterceptor {

    /** The sheet-scoped requests the guard watches. */
    public static final String PATTERN = "/api/sheets/{sheetId}/**";

    /** The requests a viewer may make: loading a sheet, and the bulk actions that refuse every item. */
    private static final Set<String> LET_THROUGH = Set.of(
            "/api/sheets/{sheetId}/load",
            "/api/sheets/{sheetId}/claims/open",
            "/api/sheets/{sheetId}/claims/settle",
            "/api/sheets/{sheetId}/people/grade");

    private final AntPathMatcher paths = new AntPathMatcher();
    private final SettingService settings;

    public ViewerGuard(SettingService settings) {
        this.settings = settings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!paths.match(PATTERN, path) || LET_THROUGH.stream().anyMatch(p -> paths.match(p, path))) {
            return true;
        }
        String header = request.getHeader(SignedInUser.HEADER);
        if (header == null || header.isBlank()) {
            return true;
        }
        Map<String, String> vars = paths.extractUriTemplateVariables(PATTERN, path);
        String sheetId = URLDecoder.decode(vars.get("sheetId"), StandardCharsets.UTF_8).trim();
        if (settings.isViewer(sheetId, SignedInUser.decode(header))) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return false;
        }
        return true;
    }
}
