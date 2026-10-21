package net.officefloor.hq.app.setting;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Refuse every change a viewer asks of a sheet: any request on a sheet other than a read, before it reaches its
 * handler, so it writes nothing and records nothing. Loading the sheet is a read. Opening or settling claims
 * together goes through, so that each claim is refused on its own and counted as refused; so does grading people
 * together, which changes no one for a viewer. */
@Configuration
public class ViewerGuard implements HandlerInterceptor, WebMvcConfigurer {

    private final SettingService settings;

    public ViewerGuard(SettingService settings) {
        this.settings = settings;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/{sheet}/**").excludePathPatterns("/api/sheets/load",
                "/api/sheets/{sheet}/claims/open", "/api/sheets/{sheet}/claims/settle",
                "/api/sheets/{sheet}/people/grade");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String user = request.getHeader("X-User");
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request
                .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String sheetId = vars == null ? null : vars.getOrDefault("sheetId", vars.get("id"));
        if (user != null && sheetId != null && settings.roleOf(sheetId, user.trim()) == Role.VIEWER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A viewer cannot change anything.");
        }
        return true;
    }
}
