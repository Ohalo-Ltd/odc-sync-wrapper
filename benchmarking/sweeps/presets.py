"""Predefined configurations from shell scripts."""

# Default annotation IDs to preload (from shell scripts)
DEFAULT_PRELOAD_ANNOTATION_IDS = ",".join(str(i) for i in range(10000, 10030))

# Sequential sweep defaults (from script_helper.sh)
SEQUENTIAL_SWEEP_DEFAULTS = {
    "datasource_counts": [1, 2, 4, 8, 16, 32, 64],
    "files_per_test": 10,
    "samples_dir": "./samples/macnica",
}

# Rate-limited sweep defaults (from script_helper_load.sh)
RATE_LIMITED_SWEEP_DEFAULTS = {
    "datasource_counts": [32, 64, 128, 256, 512, 1024],
    "files_per_second": 20,
    "duration_seconds": 60,
    "samples_dir": "./samples/plain_txt",
}

# Common Docker configuration (from both shell scripts)
DOCKER_DEFAULTS = {
    "container_name": "odc-sync",
    "port": 8844,
    "startup_wait_seconds": 20,
    "first_datasource_id": 100,
    "max_batch_size": 1000,
    "batch_interval_ms": 1000,
    "job_status_poll_interval_ms": 333,
    "name_cache_expiry_ms": 36000000,
}
