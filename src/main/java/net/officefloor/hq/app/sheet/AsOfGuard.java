package net.officefloor.hq.app.sheet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Refuses every change asked from a screen showing the sheet as of a date (one sending
 *  {@value #HEADER}) (409), before it writes anything; only the requests marked {@link AsOfAllowed}
 *  go through. */
@Configuration
public class AsOfGuard implements HandlerInterceptor, WebMvcConfigurer {

    /** The header a screen showing the sheet as of a date sends that date (yyyy-mm-dd) in. */
    public static final String HEADER = "X-As-Of";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/sheets/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String asOf = request.getHeader(HEADER);
        if (asOf == null || asOf.isBlank() || HttpMethod.GET.matches(request.getMethod())
                || !(handler instanceof HandlerMethod method) || method.hasMethodAnnotation(AsOfAllowed.class)) {
            return true;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The sheet is shown as of a date: it may not be changed from there");
    }
}
