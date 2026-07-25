package com.company.platform.file.storage;

import io.minio.MinioClient;
import java.net.InetSocketAddress;
import java.net.Proxy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

  @Bean
  public MinioClient minioClient(MinioProperties properties) {
    Proxy proxy = resolveSystemProxy();
    MinioClient minioClient;
    if (proxy == null) {
      minioClient =
          MinioClient.builder()
              .endpoint(properties.endpoint())
              .credentials(properties.accessKey(), properties.secretKey())
              .build();
    } else {
      log.debug("Using system proxy {} for MinIO client", proxy);
      OkHttpClient httpClient = new OkHttpClient.Builder().proxy(proxy).build();
      minioClient =
          MinioClient.builder()
              .endpoint(properties.endpoint())
              .credentials(properties.accessKey(), properties.secretKey())
              .httpClient(httpClient)
              .build();
    }

    minioClient.setTimeout(
        properties.connectTimeoutMillis(),
        properties.writeTimeoutMillis(),
        properties.readTimeoutMillis());
    return minioClient;
  }

  private Proxy resolveSystemProxy() {
    String proxyHost = System.getProperty("http.proxyHost");
    String proxyPort = System.getProperty("http.proxyPort");
    if (proxyHost == null || proxyPort == null) {
      return null;
    }
    return new Proxy(
        Proxy.Type.HTTP, new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort)));
  }
}
