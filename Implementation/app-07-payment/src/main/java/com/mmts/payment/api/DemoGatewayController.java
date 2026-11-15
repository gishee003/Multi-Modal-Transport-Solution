package com.mmts.payment.api;

import com.mmts.payment.adapter.StubRazorpayGateway;
import com.mmts.payment.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Demo-only controls (demo profile): make the stub gateway decline, and sign a webhook body.
 * Like the shared /dev helpers, they are open without a token and do not exist outside the demo.
 */
@RestController
@RequestMapping("/dev")
@Profile("demo")
@Tag(name = "Demo helpers")
public class DemoGatewayController {

    private final StubRazorpayGateway gateway;
    private final WebhookService webhookService;

    public DemoGatewayController(StubRazorpayGateway gateway, WebhookService webhookService) {
        this.gateway = gateway;
        this.webhookService = webhookService;
    }

    @PostMapping("/gateway/decline-next")
    @Operation(summary = "Make the next gateway charge (payment or top-up) decline, to show FAILED handling")
    public Map<String, Object> declineNext() {
        gateway.declineNextCharge();
        return Map.of("declineNextCharge", true);
    }

    @PostMapping("/webhooks/sign")
    @Operation(summary = "Compute the X-Razorpay-Signature for a webhook body (what Razorpay would send)")
    public Map<String, String> sign(@RequestBody String body) {
        return Map.of("signature", webhookService.sign(body));
    }
}
