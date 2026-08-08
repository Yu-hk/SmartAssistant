package com.example.smartassistant.consumer.client;

import com.example.smartassistant.common.location.DeviceLocation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class RouterClientDeviceLocationTest {

    @Test
    void forwardsAuthorizedDeviceLocationAsStructuredJson() {
        RouterClient client = new RouterClient(null, new ObjectMapper(), 1000, 1000);
        ReflectionTestUtils.setField(client, "routerServiceUrl", "http://router.test");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        long capturedAt = System.currentTimeMillis();

        server.expect(requestTo("http://router.test/api/router/route"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "question": "查询天气",
                          "requestId": "location-request-1",
                          "deviceLocation": {
                            "latitude": 39.9042,
                            "longitude": 116.4074,
                            "accuracyMeters": 1000.0,
                            "capturedAt": %d
                          }
                        }
                        """.formatted(capturedAt), false))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.triggerRoutingDecision(
                "查询天气", "1", "location-request-1",
                new DeviceLocation(39.9042, 116.4074, 1000d, capturedAt));

        server.verify();
    }
}
