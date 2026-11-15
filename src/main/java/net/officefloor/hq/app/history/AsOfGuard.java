package net.officefloor.hq.app.history;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** While a user's screen shows a sheet as of a date, every request it makes carries that date in
 *  {@link #HEADER}: each read answers with the sheet as it stood at the end of that date, and every
 *  changing request is refused (403, the date echoed in {@link #HEADER}) before it reaches its
 *  handler, so it writes nothing; a handler
 *  marked {@link AsOfAllowed} changes nothing and is let through. */
@Configuration
public class AsOfGuard implements HandlerInterceptor, WebMvcConfigurer {

    /** The header carrying the date (yyyy-mm-dd) the screen shows the sheet as of. */
    public static final String HEADER = "X-As-Of";

    private static final Set<String> READING = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String asOf = request.getHeader(HEADER);
        if (asOf == null || asOf.isBlank() || READING.contains(request.getMethod())
                || !(handler instanceof HandlerMethod method) || method.hasMethodAnnotation(AsOfAllowed.class)) {
            return true;
        }
        response.setHeader(HEADER, asOf.trim());
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "The sheet is shown as of " + asOf.trim() + ": clear the as-of date to change anything");
    }
}
