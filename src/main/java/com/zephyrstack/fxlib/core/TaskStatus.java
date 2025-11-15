package com.zephyrstack.fxlib.core;

/**
 * State machine for {@link FxTaskRunner} submissions.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
