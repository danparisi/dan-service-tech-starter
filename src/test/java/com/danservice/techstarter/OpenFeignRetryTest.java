package com.danservice.techstarter;

import com.danservice.techstarter.OpenFeignRetryTest.OpenFeignRetryTestConfiguration.OpenFeignRetryTestClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
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

import static com.danservice.techstarter.OpenFeignRetryTest.OpenFeignRetryTestConfiguration;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static reactor.core.publisher.Flux.just;

@Import(OpenFeignRetryTestConfiguration.class)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        "spring.cloud.openfeign.client.config.default.read-timeout=400",
        "resilience4j.timelimiter.configs.default.timeoutDuration=30000"
})
class OpenFeignRetryTest {
    private static final String SCENARIO_END = "end";
    private static final String ENDPOINT_V1_TEST = "/v1/test";
    private static final String A_RESPONSE_BODY = "a-response-body";
    private static final String SCENARIO_RETRY = "retry-scenario";
    private static final String SCENARIO_ATTEMPT_1 = "first-attempt";
    private static final String SCENARIO_ATTEMPT_2 = "second-attempt";

    @Autowired
    private WireMockServer wireMockServer;
    @Autowired
    private OpenFeignRetryTestClient openFeignTestClient;

    @BeforeEach
    void beforeEach() {
        wireMockServer.resetAll();
    }

    @Test
    void shouldRetryIfReadTimeout() {
        stubTestCall(STARTED, SCENARIO_ATTEMPT_1, 500);
        stubTestCall(SCENARIO_ATTEMPT_1, SCENARIO_ATTEMPT_2, 500);
        stubTestCall(SCENARIO_ATTEMPT_2, SCENARIO_END, 0);

        Optional<String> response = openFeignTestClient.getTestData();

        assertTrue(response.isPresent());
        assertEquals(A_RESPONSE_BODY, response.get());
        wireMockServer.verify(3, getRequestedFor(urlEqualTo(ENDPOINT_V1_TEST)));
    }

    private void stubTestCall(String scenarioState, String nextState, int fixedDelay) {
        wireMockServer
                .stubFor(get(ENDPOINT_V1_TEST)
                        .inScenario(SCENARIO_RETRY)
                        .whenScenarioStateIs(scenarioState)
                        .willReturn(aResponse()
                                .withStatus(OK.value())
                                .withBody(A_RESPONSE_BODY)
                                .withFixedDelay(fixedDelay)
                                .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)))
                .setNewScenarioState(nextState);
    }

    @TestConfiguration
    @EnableFeignClients
    public static class OpenFeignRetryTestConfiguration {
        private static final String TEST_SERVICE = "retry-test-service";

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
                    return just(List.of(
                            /*  We need 3 instances as when retrying the previous one
                                is removed from the service discovery list */
                            serviceInstance("instance-1"),
                            serviceInstance("instance-2"),
                            serviceInstance("instance-3")
                    ));
                }

                private DefaultServiceInstance serviceInstance(String id) {
                    return new DefaultServiceInstance(id, TEST_SERVICE, "localhost", wireMockServer.port(), false);
                }
            };
        }

        @FeignClient(TEST_SERVICE)
        public interface OpenFeignRetryTestClient {

            @GetMapping("v1/test")
            Optional<String> getTestData();

        }
    }
}
