# Network Disturbance Tests

Use Android Studio Network Profiler, emulator throttling, or a local proxy to exercise:

- Timeout during current playback: expect bounded retry and buffering, not decoder teardown.
- Slow throughput: expect future variant downgrade after rolling samples.
- Interrupted transfer: expect resume from known byte boundary when Range is supported.
- Non-Range source: expect sequential serving and Range support marked `NO`.
- Inconsistent `Content-Range`: expect unhealthy variant and no corrupted byte serving.
- Source unreachable: expect proxy failure metric and recoverable item error.
