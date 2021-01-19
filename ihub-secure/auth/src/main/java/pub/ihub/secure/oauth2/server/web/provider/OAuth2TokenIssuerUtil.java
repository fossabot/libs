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

package pub.ihub.secure.oauth2.server.web.provider;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken2;
import pub.ihub.secure.oauth2.jose.JoseHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import pub.ihub.secure.oauth2.jwt.JwtClaimsSet;
import pub.ihub.secure.oauth2.jwt.JwtEncoder;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames.SCOPE;
import static org.springframework.security.oauth2.core.oidc.IdTokenClaimNames.AZP;
import static org.springframework.security.oauth2.core.oidc.IdTokenClaimNames.NONCE;
import static pub.ihub.secure.oauth2.jose.JoseHeader.withAlgorithm;
import static org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256;
import static org.springframework.security.oauth2.jwt.JwtClaimNames.AUD;
import static org.springframework.security.oauth2.jwt.JwtClaimNames.EXP;
import static org.springframework.security.oauth2.jwt.JwtClaimNames.IAT;
import static org.springframework.security.oauth2.jwt.JwtClaimNames.ISS;
import static org.springframework.security.oauth2.jwt.JwtClaimNames.NBF;
import static org.springframework.security.oauth2.jwt.JwtClaimNames.SUB;

/**
 * @author henry
 */
final class OAuth2TokenIssuerUtil {

	private static final StringKeyGenerator TOKEN_GENERATOR = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);

	static Jwt issueJwtAccessToken(JwtEncoder jwtEncoder, String subject, String audience, Set<String> scopes, Duration tokenTimeToLive) {
		JoseHeader joseHeader = withAlgorithm(RS256);

		String issuer = "http://auth-server:9000";        // TODO Allow configuration for issuer claim
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(tokenTimeToLive);

		return jwtEncoder.encode(joseHeader, new JwtClaimsSet(new HashMap<>(7) {
			{
				put(ISS, issuer);
				put(SUB, subject);
				put(AUD, singletonList(audience));
				put(IAT, issuedAt);
				put(EXP, expiresAt);
				put(NBF, issuedAt);
				put(SCOPE, scopes);
			}
		}));
	}

	static Jwt issueIdToken(JwtEncoder jwtEncoder, String subject, String audience, String nonce) {
		JoseHeader joseHeader = withAlgorithm(RS256);

		String issuer = "http://auth-server:9000";        // TODO Allow configuration for issuer claim
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(30, ChronoUnit.MINUTES);        // TODO Allow configuration for id token time-to-live

		Map<String, Object> claims = new HashMap<>(7) {
			{
				put(ISS, issuer);
				put(SUB, subject);
				put(AUD, singletonList(audience));
				put(IAT, issuedAt);
				put(EXP, expiresAt);
				put(AZP, audience);
			}
		};
		if (StringUtils.hasText(nonce)) {
			claims.put(NONCE, nonce);
		}

		// TODO Add 'auth_time' claim

		return jwtEncoder.encode(joseHeader, new JwtClaimsSet(claims));
	}

	static OAuth2RefreshToken issueRefreshToken(Duration tokenTimeToLive) {
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(tokenTimeToLive);
		return new OAuth2RefreshToken2(TOKEN_GENERATOR.generateKey(), issuedAt, expiresAt);
	}

}