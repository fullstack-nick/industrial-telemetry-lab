package dev.telemetrylab.platform;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
@EnableConfigurationProperties(PlatformProperties.class)
class PlatformConfiguration {
  @Bean
  S3Client s3Client(PlatformProperties properties) {
    return S3Client.builder()
        .endpointOverride(URI.create(properties.rawStoreEndpoint()))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    properties.rawStoreAccessKey(), properties.rawStoreSecretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build();
  }
}
