package net.officefloor.hq.app;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the {@link PreviewInterceptor} in front of every API request. */
@Configuration
public class PreviewConfig implements WebMvcConfigurer {

    private final PreviewInterceptor preview;

    public PreviewConfig(PreviewInterceptor preview) {
        this.preview = preview;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(preview).addPathPatterns("/api/**");
    }
}
