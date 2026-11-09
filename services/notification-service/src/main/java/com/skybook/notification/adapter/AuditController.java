package com.skybook.notification.adapter;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.common.security.Roles;
import com.skybook.notification.domain.AuditRecord;
import com.skybook.notification.port.AuditStore;

/** Lets agents see which e-mails a booking received (support: "I never got my ticket"). */
@RestController
class AuditController {

    private final AuditStore audit;

    AuditController(AuditStore audit) {
        this.audit = audit;
    }

    @GetMapping("/api/v1/admin/notifications")
    @PreAuthorize(Roles.IS_STAFF)
    List<AuditRecord> forBooking(@RequestParam String bookingReference) {
        return audit.forBooking(bookingReference);
    }
}
