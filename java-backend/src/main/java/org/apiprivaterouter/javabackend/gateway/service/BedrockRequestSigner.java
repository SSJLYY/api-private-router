package org.apiprivaterouter.javabackend.gateway.service;

import org.apiprivaterouter.javabackend.admin.account.model.AdminAccountResponse;
import org.apiprivaterouter.javabackend.common.api.AnthropicApiErrorException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class BedrockRequestSigner {

    private static final String DEFAULT_REGION = "us-east-1";

    private final Clock clock = Clock.systemUTC();

    public String resolveRegion(AdminAccountResponse account) {
        String region = stringValue(account == null ? null : account.credentials(), "aws_region");
        return region == null ? DEFAULT_REGION : region;
    }

    public boolean isApiKeyMode(AdminAccountResponse account) {
        return "apikey".equalsIgnoreCase(stringValue(account == null ? null : account.credentials(), "auth_mode"));
    }

    public HttpRequest buildSignedRequest(URI uri, byte[] body, String region, AdminAccountResponse account) {
        AwsCredentials credentials = resolveCredentials(account);
        SdkHttpFullRequest unsigned = SdkHttpFullRequest.builder()
                .uri(uri)
                .method(software.amazon.awssdk.http.SdkHttpMethod.POST)
                .putHeader("Content-Type", "application/json")
                .putHeader("Accept", "application/json")
                .contentStreamProvider(() -> new java.io.ByteArrayInputStream(body))
                .build();

        Aws4Signer signer = Aws4Signer.create();
        SdkHttpFullRequest signed = signer.sign(unsigned, Aws4SignerParams.builder()
                .signingName("bedrock")
                .signingRegion(Region.of(region == null || region.isBlank() ? DEFAULT_REGION : region))
                .awsCredentials(credentials)
                .signingClockOverride(clock)
                .build());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for (Map.Entry<String, List<String>> entry : signed.headers().entrySet()) {
            String name = entry.getKey();
            if (name == null || entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                builder.header(name, value);
            }
        }
        return builder.build();
    }

    public HttpRequest buildApiKeyRequest(URI uri, byte[] body, AdminAccountResponse account) {
        String apiKey = stringValue(account == null ? null : account.credentials(), "api_key");
        if (apiKey == null) {
            throw new AnthropicApiErrorException(503, "api_error", "api_key not found in bedrock credentials");
        }
        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private AwsCredentials resolveCredentials(AdminAccountResponse account) {
        String accessKeyId = stringValue(account == null ? null : account.credentials(), "aws_access_key_id");
        if (accessKeyId == null) {
            throw new AnthropicApiErrorException(503, "api_error", "aws_access_key_id not found in credentials");
        }
        String secretAccessKey = stringValue(account == null ? null : account.credentials(), "aws_secret_access_key");
        if (secretAccessKey == null) {
            throw new AnthropicApiErrorException(503, "api_error", "aws_secret_access_key not found in credentials");
        }
        String sessionToken = stringValue(account == null ? null : account.credentials(), "aws_session_token");
        if (sessionToken != null) {
            return AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken);
        }
        return AwsBasicCredentials.create(accessKeyId, secretAccessKey);
    }

    private String stringValue(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
