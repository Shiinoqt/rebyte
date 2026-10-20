package it.rebyte.catalog.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import it.rebyte.catalog.dto.RebuildStatusResponse;
import it.rebyte.catalog.search.IndexDriftCheckJob;
import it.rebyte.catalog.search.IndexDriftCheckJob.DriftReport;
import it.rebyte.catalog.search.IndexRebuildJob;

@RestController
@RequestMapping("/admin/search")
@PreAuthorize("hasRole('ADMIN')")
public class SearchIndexAdminController {

    private final IndexRebuildJob rebuild;
    private final IndexDriftCheckJob drift;

    public SearchIndexAdminController(IndexRebuildJob rebuild, IndexDriftCheckJob drift) {
        this.rebuild = rebuild;
        this.drift = drift;
    }

    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RebuildStatusResponse rebuild() {
        if (!rebuild.start()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "an index rebuild is already running");
        }
        return rebuild.status();
    }

    @GetMapping("/rebuild")
    public RebuildStatusResponse rebuildStatus() {
        return rebuild.status();
    }

    @PostMapping("/drift-check")
    public DriftReport driftCheck() {
        return drift.check();
    }
}
