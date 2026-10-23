package net.officefloor.hq.app.role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import net.officefloor.hq.app.SignedIn;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Refuse every change a viewer asks for on a sheet: any request other than a read, or the loading
 * of the sheet, is turned away before it reaches its handler, so it writes nothing and audits nothing.
 * A bulk open, settle or void, a bulk grade, a bulk retire and a plan generate are the exceptions: each reaches its handler, which
 * refuses each claim or person a viewer selected, or each claim a viewer's generate would make.
 */
@Configuration
public class ViewerGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final RoleService roles;

    public ViewerGuard(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // A bulk move answers how many claims it refused, and a bulk grade or retire how many people it changed,
        // and a plan generate how many claims it made, so a viewer's is let through and refuses each.
        registry.addInterceptor(this).addPathPatterns("/api/sheets/*/**").excludePathPatterns("/api/sheets/*/load",
                "/api/sheets/*/claims/open", "/api/sheets/*/claims/settle", "/api/sheets/*/claims/void",
                "/api/sheets/*/people/grade",
                "/api/sheets/*/people/retire", "/api/sheets/*/plans/generate");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())) {
            return true;
        }
        String header = request.getHeader(SignedIn.HEADER);
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (header == null || !(variables instanceof Map<?, ?> vars)) {
            return true;
        }
        Object sheetId = vars.containsKey("sheetId") ? vars.get("sheetId") : vars.get("id");
        if (sheetId instanceof String id && Role.VIEWER.equals(roles.roleOf(id, SignedIn.name(header)))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A viewer may not change anything on the sheet.");
        }
        return true;
    }
}
