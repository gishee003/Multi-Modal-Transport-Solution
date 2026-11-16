package com.mmts.payment.api;

import com.mmts.payment.api.FinanceDtos.WebhookResponse;
import com.mmts.payment.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gateway callback (Razorpay-style webhook). No login token: the gateway proves who it is with an
 * HMAC-SHA256 signature of the exact body in the X-Razorpay-Signature header.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Gateway webhook")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/razorpay")
    @Operation(summary = "Gateway callback; accepted only with a valid X-Razorpay-Signature",
            description = "Get a signature for your body from POST /dev/webhooks/sign in the demo.")
    public WebhookResponse razorpay(@RequestBody String body,
                                    @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        return webhookService.handle(body, signature);
    }
}
