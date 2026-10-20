package it.rebyte.order.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.order.dto.LicensePage;
import it.rebyte.order.service.LicenseQueryService;

@RestController
@RequestMapping("/licences")
public class LicenseController {

    private final LicenseQueryService licenses;

    public LicenseController(LicenseQueryService licenses) {
        this.licenses = licenses;
    }

    @GetMapping("/me")
    public LicensePage mine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return licenses.mine(jwt.getSubject(), cursor, size);
    }
}
