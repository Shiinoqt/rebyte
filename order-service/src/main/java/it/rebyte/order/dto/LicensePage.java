package it.rebyte.order.dto;

import java.util.List;

public record LicensePage(List<LicenseView> items, String nextCursor) {
}
