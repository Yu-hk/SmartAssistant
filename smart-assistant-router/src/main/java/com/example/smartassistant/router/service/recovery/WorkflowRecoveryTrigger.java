package com.example.smartassistant.router.service.recovery;

/** Identifies who requested a workflow recovery without granting execution authority. */
public enum WorkflowRecoveryTrigger {
    AUTO_STALE,
    USER_MANUAL,
    ADMIN_MANUAL,
    STARTUP_REPAIR
}
