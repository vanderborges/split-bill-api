package com.splitbill.infrastructure.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
@ConditionalOnProperty(prefix = "split-bill.keep-alive", name = "enabled", havingValue = "true")
public class KeepAliveScheduler {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveScheduler.class);

    private final RestClient restClient;
    private final String healthUrl;

    public KeepAliveScheduler(@Value("${split-bill.keep-alive.url}") String baseUrl) {
        this.healthUrl = baseUrl + "/health";

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(15));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    // Chama a URL publica (nao localhost): so trafego externo reseta o timer de inatividade do Render.
    @Scheduled(fixedDelayString = "${split-bill.keep-alive.interval-ms:600000}")
    public void ping() {
        try {
            restClient.get().uri(healthUrl).retrieve().toBodilessEntity();
            log.debug("Keep-alive ping OK em {}", healthUrl);
        } catch (Exception exception) {
            log.warn("Keep-alive ping falhou em {}: {}", healthUrl, exception.getMessage());
        }
    }
}
