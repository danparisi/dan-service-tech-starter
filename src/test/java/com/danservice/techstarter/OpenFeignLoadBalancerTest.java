package com.danservice.techstarter;

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

import static com.danservice.techstarter.OpenFeignLoadBalancerTest.OpenFeignLoadBalancerTestConfiguration;
import static com.danservice.techstarter.OpenFeignLoadBalancerTest.OpenFeignLoadBalancerTestConfiguration.OpenFeignLoadBalancerTestClient;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static reactor.core.publisher.Flux.just;

@Import(OpenFeignLoadBalancerTestConfiguration.class)
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        "spring.cloud.loadbalancer.retry.enabled=false",
        "spring.cloud.openfeign.client.config.default.read-timeout=100",
        "resilience4j.timelimiter.configs.default.timeoutDuration=30000"
})
class OpenFeignLoadBalancerTest {
    private static final String ENDPOINT_V1_TEST = "/v1/test";
    private static final String A_RESPONSE_BODY = "a-response-body";

    @Autowired
    private WireMockServer mockServiceInstance1;
    @Autowired
    private WireMockServer mockServiceInstance2;
    @Autowired
    private WireMockServer mockServiceInstance3;
    @Autowired
    private OpenFeignLoadBalancerTestClient openFeignTestClient;

    @BeforeEach
    void beforeEach() {
        mockServiceInstance1.resetAll();
        mockServiceInstance2.resetAll();
        mockServiceInstance3.resetAll();
    }

    @Test
    void shouldLoadBalanceBetweenInstances() {
        stubGetRequestWithFixedDelay(mockServiceInstance1);
        stubGetRequestWithFixedDelay(mockServiceInstance2);
        stubGetRequestWithFixedDelay(mockServiceInstance3);

        doGet();
        doGet();
        doGet();

        mockServiceInstance1.verify(1, getRequestedFor(urlEqualTo(ENDPOINT_V1_TEST)));
        mockServiceInstance2.verify(1, getRequestedFor(urlEqualTo(ENDPOINT_V1_TEST)));
        mockServiceInstance3.verify(1, getRequestedFor(urlEqualTo(ENDPOINT_V1_TEST)));
    }

    private void doGet() {
        try {
            openFeignTestClient.getTestData();
        } catch (Exception e) {
            // Do nothing, timeout error is expected
        }
    }

    private void stubGetRequestWithFixedDelay(WireMockServer mockServiceInstance) {
        mockServiceInstance
                .stubFor(get(ENDPOINT_V1_TEST)
                        .willReturn(aResponse()
                                .withStatus(OK.value())
                                .withBody(A_RESPONSE_BODY)
                                .withFixedDelay(500)
                                .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)));
    }

    @TestConfiguration
    @EnableFeignClients
    public static class OpenFeignLoadBalancerTestConfiguration {
        private static final String TEST_SERVICE = "load-balancer-test-service";

        @Bean(initMethod = "start", destroyMethod = "stop")
        public WireMockServer mockServiceInstance1() {
            return new WireMockServer(0);
        }

        @Bean(initMethod = "start", destroyMethod = "stop")
        public WireMockServer mockServiceInstance2() {
            return new WireMockServer(0);
        }

        @Bean(initMethod = "start", destroyMethod = "stop")
        public WireMockServer mockServiceInstance3() {
            return new WireMockServer(0);
        }

        @Bean
        @Primary
        public ServiceInstanceListSupplier serviceInstanceListSupplier(
                final WireMockServer mockServiceInstance1,
                final WireMockServer mockServiceInstance2,
                final WireMockServer mockServiceInstance3) {
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
                            serviceInstance("instance-1", mockServiceInstance1.port()),
                            serviceInstance("instance-2", mockServiceInstance2.port()),
                            serviceInstance("instance-3", mockServiceInstance3.port())
                    ));
                }

                private DefaultServiceInstance serviceInstance(String id, int port) {
                    return new DefaultServiceInstance(id, TEST_SERVICE, "localhost", port, false);
                }
            };
        }

        @FeignClient(TEST_SERVICE)
        public interface OpenFeignLoadBalancerTestClient {

            @GetMapping("v1/test")
            Optional<String> getTestData();

        }
    }
}
