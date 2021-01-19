/*
 * Copyright (c) 2021 Henry 李恒 (henry.box@outlook.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pub.ihub.secure.oauth2.server.web.filter;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Setter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.MultiValueMap;
import org.springframework.web.filter.OncePerRequestFilter;
import pub.ihub.secure.oauth2.server.web.OAuth2EndpointUtils;
import pub.ihub.secure.oauth2.server.web.token.OAuth2ClientAuthenticationToken;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.hutool.core.lang.Assert.isTrue;
import static cn.hutool.core.lang.Assert.notNull;
import static cn.hutool.core.util.StrUtil.isBlankIfStr;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.security.core.context.SecurityContextHolder.clearContext;
import static org.springframework.security.core.context.SecurityContextHolder.createEmptyContext;
import static org.springframework.security.core.context.SecurityContextHolder.setContext;
import static org.springframework.security.oauth2.core.ClientAuthenticationMethod.BASIC;
import static org.springframework.security.oauth2.core.ClientAuthenticationMethod.POST;
import static org.springframework.security.oauth2.core.OAuth2ErrorCodes.INVALID_CLIENT;
import static org.springframework.security.oauth2.core.OAuth2ErrorCodes.INVALID_REQUEST;
import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.CLIENT_ID;
import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.CLIENT_SECRET;
import static org.springframework.security.oauth2.core.endpoint.PkceParameterNames.CODE_VERIFIER;
import static org.springframework.security.web.authentication.www.BasicAuthenticationConverter.AUTHENTICATION_SCHEME_BASIC;
import static pub.ihub.core.ObjectBuilder.builder;
import static pub.ihub.secure.oauth2.server.web.OAuth2EndpointUtils.getParametersWithPkce;

/**
 * OAuth2.0客户端授权令牌认证过滤器
 *
 * @author henry
 */
@Setter
public class OAuth2ClientAuthenticationFilter extends OncePerRequestFilter {

	/**
	 * 认证管理器
	 */
	private final AuthenticationManager authenticationManager;
	/**
	 * 请求匹配策略
	 */
	private final RequestMatcher requestMatcher;
	/**
	 * 异常转换器
	 */
	private final HttpMessageConverter<OAuth2Error> errorHttpResponseConverter = new OAuth2ErrorHttpMessageConverter();
	/**
	 * 认证凭证转换器
	 */
	private List<AuthenticationConverter> converters;
	/**
	 * 认证成功处理器
	 */
	private AuthenticationSuccessHandler authenticationSuccessHandler;
	/**
	 * 认证失败处理
	 */
	private AuthenticationFailureHandler authenticationFailureHandler;

	public OAuth2ClientAuthenticationFilter(AuthenticationManager authenticationManager,
											RequestMatcher requestMatcher) {
		this.authenticationManager = notNull(authenticationManager, "认证管理器不能为空！");
		this.requestMatcher = notNull(requestMatcher, "请求匹配策略不能为空！");
		this.converters = Arrays.asList(
			OAuth2ClientAuthenticationFilter::clientSecretBasicConvert,
			OAuth2ClientAuthenticationFilter::clientSecretPostConvert,
			OAuth2ClientAuthenticationFilter::publicClientConvert);
		this.authenticationSuccessHandler = this::onAuthenticationSuccess;
		this.authenticationFailureHandler = this::onAuthenticationFailure;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		if (this.requestMatcher.matches(request)) {
			try {
				// 获取客户端授权令牌
				Authentication authenticationRequest = converters.stream().map(converter -> converter.convert(request))
					.filter(Objects::nonNull).findFirst().orElse(null);
				if (authenticationRequest != null) {
					Authentication authenticationResult = authenticationManager.authenticate(authenticationRequest);
					authenticationSuccessHandler.onAuthenticationSuccess(request, response, authenticationResult);
				}
			} catch (OAuth2AuthenticationException failed) {
				authenticationFailureHandler.onAuthenticationFailure(request, response, failed);
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

	/**
	 * 尝试从HttpServletRequest提取HTTP Basic凭证
	 *
	 * @param request 请求
	 * @return 认证凭证
	 */
	private static Authentication clientSecretBasicConvert(HttpServletRequest request) {
		String header = request.getHeader(AUTHORIZATION);
		if (header == null) {
			return null;
		}
		try {
			String[] parts = header.split("\\s");
			if (!parts[0].equalsIgnoreCase(AUTHENTICATION_SCHEME_BASIC)) {
				return null;
			}
			byte[] decodedCredentials = Base64.getDecoder().decode(parts[1].getBytes(UTF_8));
			String[] credentials = new String(decodedCredentials, UTF_8).split(":", 2);
			String clientId = URLDecoder.decode(credentials[0], UTF_8.name());
			String clientSecret = URLDecoder.decode(credentials[1], UTF_8.name());
			return new OAuth2ClientAuthenticationToken(clientId, clientSecret, BASIC, getParametersWithPkce(request));
		} catch (Exception ex) {
			throw new OAuth2AuthenticationException(new OAuth2Error(INVALID_REQUEST), ex);
		}
	}

	/**
	 * 尝试从HttpServletRequest POST参数中提取客户端凭据
	 *
	 * @param request 请求
	 * @return 认证凭证
	 */
	private static Authentication clientSecretPostConvert(HttpServletRequest request) {
		MultiValueMap<String, String> parameters = OAuth2EndpointUtils.getParameters(request, CLIENT_ID, CLIENT_SECRET);

		String clientId = parameters.getFirst(CLIENT_ID);
		if (StrUtil.isBlank(clientId)) {
			return null;
		}

		String clientSecret = parameters.getFirst(CLIENT_SECRET);
		if (StrUtil.isBlank(clientSecret)) {
			return null;
		}

		Map<String, Object> additionalParameters = getParametersWithPkce(request);
		additionalParameters.remove(CLIENT_ID);
		additionalParameters.remove(CLIENT_SECRET);
		return new OAuth2ClientAuthenticationToken(clientId, clientSecret, POST, additionalParameters);
	}

	/**
	 * 尝试从HttpServletRequest提取用于认证公共客户端的参数（PKCE）
	 *
	 * @param request 请求
	 * @return 认证凭证
	 */
	private static Authentication publicClientConvert(HttpServletRequest request) {
		Map<String, Object> parameters = getParametersWithPkce(request, CLIENT_ID, CODE_VERIFIER);
		if (CollUtil.isEmpty(parameters)) {
			return null;
		}

		Object clientId = parameters.get(CLIENT_ID);
		isTrue(!isBlankIfStr(clientId), () -> new OAuth2AuthenticationException(new OAuth2Error(INVALID_REQUEST)));
		parameters.remove(CLIENT_ID);

		return new OAuth2ClientAuthenticationToken(clientId.toString(), parameters);
	}

	private void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
										 Authentication authentication) {
		setContext(builder(createEmptyContext()).set(SecurityContext::setAuthentication, authentication).build());
	}

	private void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
										 AuthenticationException failed) throws IOException {
		clearContext();
		OAuth2Error error = ((OAuth2AuthenticationException) failed).getError();
		errorHttpResponseConverter.write(error, null,
			builder(ServletServerHttpResponse::new, response).set(ServletServerHttpResponse::setStatusCode,
				INVALID_CLIENT.equals(error.getErrorCode()) ? UNAUTHORIZED : BAD_REQUEST).build());
	}

}