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

import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames2;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import pub.ihub.secure.oauth2.server.web.OAuth2EndpointUtils;
import pub.ihub.secure.oauth2.server.web.token.OAuth2TokenRevocationAuthenticationToken;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static cn.hutool.core.lang.Assert.notBlank;
import static cn.hutool.core.lang.Assert.notNull;
import static org.springframework.http.HttpMethod.POST;

/**
 * OAuth 2.0令牌吊销终结点的Filter
 * TODO 整理页面
 *
 * @author henry
 */
public class OAuth2TokenRevocationEndpointFilter extends OncePerRequestFilter {

	public static final String DEFAULT_TOKEN_REVOCATION_ENDPOINT_URI = "/oauth2/revoke";

	private final AuthenticationManager authenticationManager;
	private final RequestMatcher tokenRevocationEndpointMatcher;
	private final Converter<HttpServletRequest, Authentication> tokenRevocationAuthenticationConverter =
		new DefaultTokenRevocationAuthenticationConverter();
	private final HttpMessageConverter<OAuth2Error> errorHttpResponseConverter =
		new OAuth2ErrorHttpMessageConverter();

	public OAuth2TokenRevocationEndpointFilter(AuthenticationManager authenticationManager) {
		this(authenticationManager, DEFAULT_TOKEN_REVOCATION_ENDPOINT_URI);
	}

	public OAuth2TokenRevocationEndpointFilter(AuthenticationManager authenticationManager,
											   String tokenRevocationEndpointUri) {
		this.authenticationManager = notNull(authenticationManager, "认证管理器不能为空！");
		this.tokenRevocationEndpointMatcher = new AntPathRequestMatcher(
			notBlank(tokenRevocationEndpointUri, "请求匹配策略不能为空！"), POST.name());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		if (!this.tokenRevocationEndpointMatcher.matches(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			this.authenticationManager.authenticate(
				this.tokenRevocationAuthenticationConverter.convert(request));
			response.setStatus(HttpStatus.OK.value());
		} catch (OAuth2AuthenticationException ex) {
			SecurityContextHolder.clearContext();
			sendErrorResponse(response, ex.getError());
		}
	}

	private void sendErrorResponse(HttpServletResponse response, OAuth2Error error) throws IOException {
		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
		httpResponse.setStatusCode(HttpStatus.BAD_REQUEST);
		this.errorHttpResponseConverter.write(error, null, httpResponse);
	}

	private static void throwError(String errorCode, String parameterName) {
		OAuth2Error error = new OAuth2Error(errorCode, "OAuth 2.0 Token Revocation Parameter: " + parameterName,
			"https://tools.ietf.org/html/rfc7009#section-2.1");
		throw new OAuth2AuthenticationException(error);
	}

	private static class DefaultTokenRevocationAuthenticationConverter
		implements Converter<HttpServletRequest, Authentication> {

		@Override
		public Authentication convert(HttpServletRequest request) {
			Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();

			MultiValueMap<String, String> parameters = OAuth2EndpointUtils.getParameters(request);

			// token (REQUIRED)
			String token = parameters.getFirst(OAuth2ParameterNames2.TOKEN);
			if (!StringUtils.hasText(token) ||
				parameters.get(OAuth2ParameterNames2.TOKEN).size() != 1) {
				throwError(OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ParameterNames2.TOKEN);
			}

			// token_type_hint (OPTIONAL)
			String tokenTypeHint = parameters.getFirst(OAuth2ParameterNames2.TOKEN_TYPE_HINT);
			if (StringUtils.hasText(tokenTypeHint) &&
				parameters.get(OAuth2ParameterNames2.TOKEN_TYPE_HINT).size() != 1) {
				throwError(OAuth2ErrorCodes.INVALID_REQUEST, OAuth2ParameterNames2.TOKEN_TYPE_HINT);
			}

			return new OAuth2TokenRevocationAuthenticationToken(token, clientPrincipal, tokenTypeHint);
		}
	}

}