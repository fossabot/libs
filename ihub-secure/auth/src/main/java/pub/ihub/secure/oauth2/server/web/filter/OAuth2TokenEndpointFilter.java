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
import cn.hutool.core.util.ObjectUtil;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse.Builder;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.util.MultiValueMap;
import pub.ihub.secure.oauth2.server.web.OAuth2ManagerFilter;
import pub.ihub.secure.oauth2.server.web.token.OAuth2AccessAuthToken;
import pub.ihub.secure.oauth2.server.web.token.OAuth2AuthCodeToken;
import pub.ihub.secure.oauth2.server.web.token.OAuth2ClientCredentialsToken;
import pub.ihub.secure.oauth2.server.web.token.OAuth2RefreshToken;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static cn.hutool.core.lang.Assert.notNull;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.springframework.security.core.context.SecurityContextHolder.clearContext;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;
import static org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse.withToken;
import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.CLIENT_ID;
import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.CODE;
import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.GRANT_TYPE;
import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.REDIRECT_URI;
import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.REFRESH_TOKEN;
import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.SCOPE;
import static pub.ihub.core.ObjectBuilder.builder;

/**
 * OAuth2.0令牌授予过滤器
 *
 * @author henry
 */
public class OAuth2TokenEndpointFilter extends OAuth2ManagerFilter {

	/**
	 * 认证凭证转换器
	 */
	private static Map<AuthorizationGrantType, Converter<HttpServletRequest, Authentication>> converters =
		new HashMap<>(3) {{
			put(AuthorizationGrantType.AUTHORIZATION_CODE, OAuth2TokenEndpointFilter::authorizationCodeConvert);
			put(AuthorizationGrantType.REFRESH_TOKEN, OAuth2TokenEndpointFilter::refreshTokenConvert);
			put(AuthorizationGrantType.CLIENT_CREDENTIALS, OAuth2TokenEndpointFilter::clientCredentialsConvert);
		}};
	/**
	 * 认证消息转换器
	 */
	private final HttpMessageConverter<OAuth2AccessTokenResponse> accessTokenHttpResponseConverter =
		new OAuth2AccessTokenResponseHttpMessageConverter();

	public OAuth2TokenEndpointFilter(AuthenticationManager authenticationManager, String tokenEndpointUri) {
		super(authenticationManager, tokenEndpointUri, OAuth2TokenEndpointFilter::convert);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		if (!requestMatcher.matches(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			sendAccessTokenResponse(response, (OAuth2AccessAuthToken) authenticationManager
				.authenticate(notNull(authenticationConverter.convert(request), exceptionSupplier(GRANT_TYPE))));
		} catch (OAuth2AuthenticationException ex) {
			clearContext();
			sendErrorResponse(response, ex.getError());
		}
	}

	private static Authentication convert(HttpServletRequest request) {
		Converter<HttpServletRequest, Authentication> converter = converters
			.get(new AuthorizationGrantType(getParameterValue(request, GRANT_TYPE)));
		if (converter == null) {
			return null;
		}
		return converter.convert(request);
	}

	private static Authentication authorizationCodeConvert(HttpServletRequest request) {
		MultiValueMap<String, String> parameters = getParameters(request);
		return new OAuth2AuthCodeToken(
			getParameterValue(parameters, CODE),
			getContext().getAuthentication(),
			getParameterValue(parameters, REDIRECT_URI, true),
			filterParameters(parameters, GRANT_TYPE, CLIENT_ID, CODE, REDIRECT_URI));
	}

	private static Authentication refreshTokenConvert(HttpServletRequest request) {
		MultiValueMap<String, String> parameters = getParameters(request, SCOPE);
		return new OAuth2RefreshToken(
			getParameterValue(parameters, REFRESH_TOKEN),
			getContext().getAuthentication(),
			extractScopes(parameters));
	}

	private static Authentication clientCredentialsConvert(HttpServletRequest request) {
		return new OAuth2ClientCredentialsToken(
			getContext().getAuthentication(),
			extractScopes(getParameters(request, SCOPE)));
	}

	private void sendAccessTokenResponse(HttpServletResponse response, OAuth2AccessAuthToken accessTokenAuthentication)
		throws IOException {
		OAuth2AccessToken accessToken = accessTokenAuthentication.getAccessToken();
		accessTokenHttpResponseConverter.write(
			builder(withToken(accessToken.getTokenValue())
				.tokenType(accessToken.getTokenType())
				.scopes(accessToken.getScopes()))
				.set(token -> token.getIssuedAt() != null && token.getExpiresAt() != null,
					Builder::expiresIn, accessToken,
					token -> SECONDS.between(token.getIssuedAt(), token.getExpiresAt()))
				.set(ObjectUtil::isNotNull, Builder::refreshToken,
					accessTokenAuthentication.getRefreshToken(), AbstractOAuth2Token::getTokenValue)
				.set(CollUtil::isNotEmpty, Builder::additionalParameters, accessTokenAuthentication.getAdditionalParameters())
				.build().build(),
			null,
			new ServletServerHttpResponse(response)
		);
	}

}
