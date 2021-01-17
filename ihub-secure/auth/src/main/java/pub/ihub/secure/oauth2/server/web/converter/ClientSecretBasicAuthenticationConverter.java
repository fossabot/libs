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

package pub.ihub.secure.oauth2.server.web.converter;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;
import pub.ihub.secure.oauth2.server.web.OAuth2EndpointUtils;
import pub.ihub.secure.oauth2.server.web.token.OAuth2ClientAuthenticationToken;

import javax.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 尝试从HttpServletRequest提取HTTP基本凭证
 *
 * @author henry
 */
public final class ClientSecretBasicAuthenticationConverter implements AuthenticationConverter {

	@Override
	public Authentication convert(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null) {
			return null;
		}

		String[] parts = header.split("\\s");
		if (!parts[0].equalsIgnoreCase("Basic")) {
			return null;
		}

		if (parts.length != 2) {
			throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST));
		}

		byte[] decodedCredentials;
		try {
			decodedCredentials = Base64.getDecoder().decode(
				parts[1].getBytes(StandardCharsets.UTF_8));
		} catch (IllegalArgumentException ex) {
			throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST), ex);
		}

		String credentialsString = new String(decodedCredentials, StandardCharsets.UTF_8);
		String[] credentials = credentialsString.split(":", 2);
		if (credentials.length != 2 ||
			!StringUtils.hasText(credentials[0]) ||
			!StringUtils.hasText(credentials[1])) {
			throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST));
		}

		String clientID;
		String clientSecret;
		try {
			clientID = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8.name());
			clientSecret = URLDecoder.decode(credentials[1], StandardCharsets.UTF_8.name());
		} catch (Exception ex) {
			throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST), ex);
		}

		return new OAuth2ClientAuthenticationToken(clientID, clientSecret, ClientAuthenticationMethod.BASIC,
			extractAdditionalParameters(request));
	}

	private static Map<String, Object> extractAdditionalParameters(HttpServletRequest request) {
		Map<String, Object> additionalParameters = Collections.emptyMap();
		if (OAuth2EndpointUtils.matchesPkceTokenRequest(request)) {
			// Confidential clients can also leverage PKCE
			additionalParameters = new HashMap<>(OAuth2EndpointUtils.getParameters(request).toSingleValueMap());
		}
		return additionalParameters;
	}

}
