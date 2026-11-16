package com.mmts.provider.api;

import com.mmts.provider.common.security.AuthenticatedUser;
import com.mmts.provider.common.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** "Who am I" endpoint: shows which application answered and who the token belongs to. */
@RestController
@RequestMapping("/api/v1/info")
public class InfoController {

    private final String appId;
    private final String appName;

    public InfoController(@Value("${mmts.app-id}") String appId, @Value("${spring.application.name}") String appName) {
        this.appId = appId;
        this.appName = appName;
    }

    @GetMapping
    @Operation(summary = "Which application this is, and who is calling")
    public InfoResponse info(@AuthenticationPrincipal AuthenticatedUser user) {
        return new InfoResponse(appId, appName, user.userId(), user.roles());
    }

    public record InfoResponse(String appId, String application, String userId, Set<Role> roles) {
    }
}
