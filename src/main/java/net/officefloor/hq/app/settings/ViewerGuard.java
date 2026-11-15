package net.officefloor.hq.app.settings;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.session.CurrentUser;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Refuses (403) every changing request on a sheet by a user who is a viewer on it, and every
 *  changing request on an archived sheet, before it reaches its handler, so it writes nothing; a
 *  handler marked {@link ViewerAllowed} sees to the viewer and the archived sheet itself. A changing request by a user acting under a steward's delegation is marked
 *  for its audit records ({@link Audit#FOR}). */
@Configuration
public class ViewerGuard implements HandlerInterceptor, WebMvcConfigurer {

    private static final Set<String> READING = Set.of("GET", "HEAD", "OPTIONS");

    private final SettingsService settings;
    private final SheetService sheets;

    public ViewerGuard(SettingsService settings, SheetService sheets) {
        this.settings = settings;
        this.sheets = sheets;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (READING.contains(request.getMethod()) || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        String user = request.getHeader(CurrentUser.HEADER);
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String sheetId = vars == null ? null : vars.getOrDefault("sheetId", vars.get("id"));
        if (user != null && sheetId != null) {
            String steward = settings.delegatorOf(sheetId, user.trim());
            if (steward != null) {
                request.setAttribute(Audit.FOR, steward);
            }
        }
        if (method.hasMethodAnnotation(ViewerAllowed.class)) {
            return true;
        }
        if (sheetId != null && sheets.archived(sheetId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The sheet is archived: nothing on it may change");
        }
        if (user != null && sheetId != null && settings.isViewer(sheetId, user.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A viewer may change nothing on this sheet");
        }
        return true;
    }
}
