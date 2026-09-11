package com.example.chat.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

// board의 JwtAuthenticationFilter와 같은 원칙: 인증 정보를 "심기"만 하고 막지 않는다.
// 토큰이 없거나 무효면 컨텍스트를 비운 채 통과 → AuthorizationFilter가 401(entryPoint)로 응답한다.
// 내부 오류(Redis·DB)는 401로 둔갑시키지 않고 HandlerExceptionResolver로 위임해 500이 되게 한다.
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final BearerTokenAuthenticator authenticator;
  private final HandlerExceptionResolver handlerExceptionResolver;

  public JwtAuthenticationFilter(
      BearerTokenAuthenticator authenticator,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
    this.authenticator = authenticator;
    this.handlerExceptionResolver = handlerExceptionResolver;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    try {
      Optional<String> token =
          BearerTokenAuthenticator.extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
      if (token.isPresent()) {
        authenticator.authenticate(token.get()).ifPresent(principal -> {
          UsernamePasswordAuthenticationToken authentication =
              (UsernamePasswordAuthenticationToken) principal.toAuthentication();
          authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
          SecurityContextHolder.getContext().setAuthentication(authentication);
        });
      }
    } catch (Exception e) {
      SecurityContextHolder.clearContext();
      handlerExceptionResolver.resolveException(request, response, null, e);
      return;
    }
    filterChain.doFilter(request, response);
  }
}
