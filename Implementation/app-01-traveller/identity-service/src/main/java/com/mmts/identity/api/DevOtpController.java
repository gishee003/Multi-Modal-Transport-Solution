package com.mmts.identity.api;

import com.mmts.identity.adapter.StubSmsGateway;
import com.mmts.identity.common.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demo only: shows the last SMS the stub gateway "sent" to a mobile number, so OTP login can be shown without a phone.
 * It talks to the stub adapter directly because it exists only to look inside the stub; it does not exist
 * outside the demo profile.
 */
@RestController
@RequestMapping("/dev/otp")
@Profile("demo")
@Tag(name = "Demo helpers", description = "Only available in the demo profile")
public class DevOtpController {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    private final StubSmsGateway smsGateway;

    public DevOtpController(StubSmsGateway smsGateway) {
        this.smsGateway = smsGateway;
    }

    @GetMapping("/{mobile}")
    @Operation(summary = "Last OTP sent to a mobile number (demo only)")
    public LastOtp lastOtp(@PathVariable String mobile) {
        StubSmsGateway.SentSms sms = smsGateway.lastMessageTo(mobile)
                .orElseThrow(() -> ApiException.notFound("NO_SMS_SENT", "No SMS has been sent to this number"));
        Matcher m = SIX_DIGITS.matcher(sms.text());
        return new LastOtp(mobile, m.find() ? m.group(1) : null, sms.text(), sms.sentAt());
    }

    public record LastOtp(String mobile, String otp, String message, Instant sentAt) {
    }
}
