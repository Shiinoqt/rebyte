package it.rebyte.order.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.order.dto.RefundRequest;
import it.rebyte.order.dto.RefundResponse;
import it.rebyte.order.dto.StuckOrderPage;
import it.rebyte.order.service.OrderAdminService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private final OrderAdminService admin;

    public AdminOrderController(OrderAdminService admin) {
        this.admin = admin;
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public RefundResponse refund(@PathVariable String id, @Valid @RequestBody RefundRequest request) {
        return admin.refund(id, request.reason());
    }

    @GetMapping("/stuck")
    @PreAuthorize("hasRole('ADMIN')")
    public StuckOrderPage stuck(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return admin.stuck(cursor, size);
    }
}
