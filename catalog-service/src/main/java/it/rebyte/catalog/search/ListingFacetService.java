package it.rebyte.catalog.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.dto.FacetDistribution;

/** Derives facet fields from the effective schema and values from Meilisearch results. */
@Service
public class ListingFacetService {

    public String[] fields(List<AttributeDefinition> schema) {
        return schema.stream().map(definition -> "attr." + definition.getName()).toArray(String[]::new);
    }

    public List<FacetDistribution> distributions(List<AttributeDefinition> schema, Object rawDistribution) {
        if (!(rawDistribution instanceof Map<?, ?> raw)) {
            return List.of();
        }
        List<FacetDistribution> result = new ArrayList<>();
        for (AttributeDefinition definition : schema) {
            Object fieldDistribution = raw.get("attr." + definition.getName());
            if (!(fieldDistribution instanceof Map<?, ?> values)) {
                continue;
            }
            Map<String, Long> counts = new LinkedHashMap<>();
            values.forEach((value, count) -> counts.put(String.valueOf(value), ((Number) count).longValue()));
            result.add(new FacetDistribution(definition.getName(), counts));
        }
        return List.copyOf(result);
    }
}
