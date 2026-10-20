package it.rebyte.catalog.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PhotoUrls {

    private final String distributionDomain;

    public PhotoUrls(@Value("${rebyte.media.distribution-domain:}") String distributionDomain) {
        this.distributionDomain = distributionDomain;
    }

    public String toUrl(String key) {
        if (key == null || key.isBlank()) return null;
        if (distributionDomain == null || distributionDomain.isBlank()) return key;
        String domain = distributionDomain.startsWith("http") ? distributionDomain : "https://" + distributionDomain;
        return domain.replaceAll("/$", "") + "/" + key;
    }

    public List<String> toUrls(List<String> keys) {
        return keys.stream().map(this::toUrl).toList();
    }
}
