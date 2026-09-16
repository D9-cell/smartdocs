package com.deepon.smartdocs.common;

import com.deepon.smartdocs.security.SessionAuthFilter;
import com.deepon.smartdocs.user.exception.SessionInvalidException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where "authorization" actually happens for this stage (design doc section
 * 4.3 step 6): a controller method that declares an {@link Actor} parameter
 * requires one. {@link SessionAuthFilter} only ever populates the request
 * attribute when a session actually resolves; a missing attribute here means
 * anonymous, and this resolver is what turns that into 401 {@code SESSION_INVALID}
 * for exactly the endpoints that need it — public endpoints simply don't
 * declare the parameter.
 */
@Component
public class ActorArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Actor.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object actor = request == null ? null : request.getAttribute(SessionAuthFilter.ACTOR_ATTRIBUTE);
        if (actor == null) {
            throw new SessionInvalidException();
        }
        return actor;
    }
}
