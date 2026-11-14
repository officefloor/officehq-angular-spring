package net.officefloor.hq.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.DateTimeException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Takes the screen's preview date for a read (GET or HEAD) only, so the sheet's today stands in as
 *  that date while the read is answered; a change never sees it and uses the sheet's real today. */
@Component
public class PreviewInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Preview.clear();
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)) {
            try {
                Preview.set(Preview.parse(request.getHeader(Preview.HEADER)));
            } catch (DateTimeException e) {
                Preview.clear();
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        Preview.clear();
    }
}
