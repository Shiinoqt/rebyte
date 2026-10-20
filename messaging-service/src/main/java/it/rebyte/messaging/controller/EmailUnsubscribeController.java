package it.rebyte.messaging.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import it.rebyte.messaging.email.UnsubscribeTokens;
import it.rebyte.messaging.service.EmailPreferenceService;

@RestController
@RequestMapping("/notifications/email-preferences/unsubscribe")
public class EmailUnsubscribeController {

    private final UnsubscribeTokens tokens;
    private final EmailPreferenceService preferences;

    public EmailUnsubscribeController(UnsubscribeTokens tokens, EmailPreferenceService preferences) {
        this.tokens = tokens;
        this.preferences = preferences;
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public String confirmation(@RequestParam String token) {
        String action = UriComponentsBuilder.fromPath("/notifications/email-preferences/unsubscribe")
                .queryParam("token", "{token}").encode().buildAndExpand(token).toUriString();
        return "<!doctype html><html lang=\"en\"><head><meta name=\"referrer\" content=\"strict-origin\"></head>"
                + "<body><p>Stop message and release emails from Rebyte?</p>"
                + "<form method=\"post\" action=\"" + HtmlUtils.htmlEscape(action)
                + "\"><button type=\"submit\">Unsubscribe</button></form></body></html>";
    }

    @PostMapping(produces = MediaType.TEXT_HTML_VALUE)
    public String unsubscribe(@RequestParam String token) {
        preferences.setEnabled(tokens.verify(token), false);
        return "<!doctype html><html lang=\"en\"><body><p>You will no longer receive message and release emails "
                + "from Rebyte. Order emails are still sent.</p></body></html>";
    }
}
