package com.danservice.techstarter;

import com.danservice.techstarter.OpenFeignCircuitBreakerTest.OpenFeignCircuitBreakerTestConfiguration.OpenFeignCircuitBreakerTestClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static com.danservice.techstarter.OpenFeignCircuitBreakerTest.OpenFeignCircuitBreakerTestConfiguration;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static reactor.core.publisher.Flux.just;

@Import(OpenFeignCircuitBreakerTestConfiguration.class)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        "spring.cloud.loadbalancer.retry.enabled=false",
        "spring.cloud.openfeign.client.config.default.read-timeout=100",
        "resilience4j.timelimiter.configs.default.timeoutDuration=30000",
})
class OpenFeignCircuitBreakerTest {
    private static final String ENDPOINT_V1_TEST = "/v1/test";
    private static final String A_RESPONSE_BODY = "a-response-body";

    @Autowired
    private WireMockServer wireMockServer;
    @Autowired
    private OpenFeignCircuitBreakerTestClient openFeignTestClient;

    @BeforeEach
    void beforeEach() {
        wireMockServer.resetAll();
    }

    @Test
    void shouldOpenCircuitIfRequestFailed3Time() {
        wireMockServer
                .stubFor(get(ENDPOINT_V1_TEST)
                        .willReturn(aResponse()
                                .withStatus(OK.value())
                                .withBody(A_RESPONSE_BODY)
                                .withFixedDelay(500)
                                .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)));

        doCallAndAssertExceptionCauseIs(RetryableException.class);
        doCallAndAssertExceptionCauseIs(RetryableException.class);
        doCallAndAssertExceptionCauseIs(RetryableException.class);
        doCallAndAssertExceptionCauseIs(CallNotPermittedException.class);

        wireMockServer.verify(3, getRequestedFor(urlEqualTo(ENDPOINT_V1_TEST)));
    }

    private <T> void doCallAndAssertExceptionCauseIs(Class<T> expectedExceptionCause) {
        assertInstanceOf(expectedExceptionCause, assertThrows(NoFallbackAvailableException.class, () -> openFeignTestClient.getTestData()).getCause());
    }

    @TestConfiguration
    @EnableFeignClients
    public static class OpenFeignCircuitBreakerTestConfiguration {
        private static final String TEST_SERVICE = "circuit-breaker-test-service";

        @Bean(initMethod = "start", destroyMethod = "stop")
        public WireMockServer mockService() {
            return new WireMockServer(0);
        }

        @Bean
        @Primary
        public ServiceInstanceListSupplier serviceInstanceListSupplier(final WireMockServer wireMockServer) {
            return new ServiceInstanceListSupplier() {
                @Override
                public String getServiceId() {
                    return TEST_SERVICE;
                }

                @Override
                public Flux<List<ServiceInstance>> get() {
                    return just(List.of(serviceInstance("instance-1")));
                }

                private DefaultServiceInstance serviceInstance(String id) {
                    return new DefaultServiceInstance(id, TEST_SERVICE, "localhost", wireMockServer.port(), false);
                }
            };
        }

        @FeignClient(TEST_SERVICE)
        public interface OpenFeignCircuitBreakerTestClient {

            @GetMapping("v1/test")
            Optional<String> getTestData();

        }
    }
}
