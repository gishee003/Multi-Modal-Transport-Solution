package com.mmts.booking.api;

import com.mmts.booking.service.DemoControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Demo only (/dev/stubs, no token needed): make the stand-ins for APP-04, APP-07 and APP-08 fail on purpose,
 * so the saga's undo can be shown live. Every call returns the current stub settings.
 */
@RestController
@RequestMapping("/dev/stubs")
@Profile("demo")
@Tag(name = "Demo stub controls", description = "Force failures in the stubs of other applications")
public class DemoStubController {

    private final DemoControlService demo;

    public DemoStubController(DemoControlService demo) {
        this.demo = demo;
    }

    @GetMapping
    @Operation(summary = "Current stub settings")
    public Map<String, Object> state() {
        return demo.state();
    }

    @PostMapping("/fail-next-payment")
    @Operation(summary = "APP-07 stub: the next payment comes back FAILED")
    public Map<String, Object> failNextPayment(@RequestParam(defaultValue = "INSUFFICIENT_BALANCE") String reason) {
        demo.failNextPayment(reason);
        return demo.state();
    }

    @PostMapping("/refuse-holds")
    @Operation(summary = "APP-08 stub: this provider refuses every hold (409 NOT_AVAILABLE)")
    public Map<String, Object> refuseHolds(@RequestParam String providerId) {
        demo.refuseHolds(providerId);
        return demo.state();
    }

    @PostMapping("/fail-next-confirm")
    @Operation(summary = "APP-08 stub: the next confirm fails (409 HOLD_NOT_ACTIVE), after payment")
    public Map<String, Object> failNextConfirm() {
        demo.failNextConfirm();
        return demo.state();
    }

    @PostMapping("/ineligible")
    @Operation(summary = "APP-04 stub: this user may not self-drive (reason e.g. EXPIRED)")
    public Map<String, Object> ineligible(@RequestParam String userId, @RequestParam(defaultValue = "EXPIRED") String reason) {
        demo.makeIneligible(userId, reason);
        return demo.state();
    }

    @PostMapping("/reset")
    @Operation(summary = "Every stub succeeds again")
    public Map<String, Object> reset() {
        demo.reset();
        return demo.state();
    }
}
