package net.officefloor.hq.app;

import net.officefloor.hq.app.settings.ViewerGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Guards the API: a viewer is refused every change. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ViewerGuard viewerGuard;

    public WebConfig(ViewerGuard viewerGuard) {
        this.viewerGuard = viewerGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(viewerGuard).addPathPatterns("/api/**");
    }
}
