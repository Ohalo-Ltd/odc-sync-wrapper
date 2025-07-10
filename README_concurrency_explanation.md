# Concurrent Batching Mechanism

The concurrent batching mechanism in the `FileBatchingService` is designed to handle multiple simultaneous file uploads while maintaining efficient batching and providing synchronous responses. Here's how it works:

## Core Components

### 1. **Shared Batch State**
```java
private final List<FileRequest> currentBatch = Collections.synchronizedList(new ArrayList<>());
private ScheduledFuture<?> batchTimer;
private final Object batchLock = new Object();
```

- `currentBatch`: Thread-safe list holding pending file requests
- `batchTimer`: Scheduled task that triggers batch processing after timeout
- `batchLock`: Synchronization object to coordinate batch operations

### 2. **File Request Structure**
```java
public static record FileRequest(MultipartFile file, CompletableFuture<FileClassificationResult> result) {}
```

Each file upload creates a `FileRequest` containing:
- The uploaded file data
- A `CompletableFuture` that will be completed with the classification result

## Batching Flow

### Step 1: File Arrival
When a file arrives via the REST API:

```java
public CompletableFuture<FileClassificationResult> processFile(MultipartFile file) {
    CompletableFuture<FileClassificationResult> result = new CompletableFuture<>();
    FileRequest request = new FileRequest(file, result);
    
    synchronized (batchLock) {
        currentBatch.add(request);
        
        // First file in batch - start the timer
        if (currentBatch.size() == 1) {
            batchTimer = scheduledExecutor.schedule(this::processBatch, batchIntervalSec, TimeUnit.SECONDS);
        }
        
        // Batch is full - process immediately
        if (currentBatch.size() >= maxBatchSize) {
            if (batchTimer != null) {
                batchTimer.cancel(false);
            }
            processBatch();
        }
    }
    
    return result; // Returns immediately to caller
}
```

### Step 2: Batch Triggering
A batch is processed when either:
- **Time-based**: `DXR_BATCH_INTERVAL_SEC` seconds elapse since the first file
- **Size-based**: `DXR_MAX_BATCH_SIZE` files are accumulated

### Step 3: Batch Processing
```java
private void processBatch() {
    List<FileRequest> batch;
    synchronized (batchLock) {
        if (currentBatch.isEmpty()) {
            return;
        }
        batch = new ArrayList<>(currentBatch);  // Copy current batch
        currentBatch.clear();                   // Clear for next batch
        batchTimer = null;
    }
    
    executorService.submit(() -> {
        // Process batch asynchronously
        // ... Data X-Ray API calls ...
        // Complete individual futures with results
    });
}
```

## Concurrency Scenarios

### Scenario 1: Sequential File Uploads
```
Time: 0s    - File A arrives → starts 30s timer
Time: 5s    - File B arrives → added to batch
Time: 10s   - File C arrives → added to batch
Time: 30s   - Timer expires → batch [A,B,C] processed
```

### Scenario 2: Rapid File Uploads (Batch Size Limit)
```
Time: 0s    - File A arrives → starts 30s timer
Time: 1s    - File B arrives → added to batch
Time: 2s    - File C arrives → added to batch
Time: 3s    - File D arrives → added to batch
Time: 4s    - File E arrives → batch size (5) reached → immediate processing
```

### Scenario 3: Overlapping Batches
```
Time: 0s    - File A arrives → Batch 1 starts
Time: 5s    - File B arrives → added to Batch 1
Time: 10s   - File C arrives → added to Batch 1
Time: 15s   - Batch 1 reaches size limit → processing starts
Time: 16s   - File D arrives → Batch 2 starts (new timer)
Time: 20s   - File E arrives → added to Batch 2
```

## Synchronous Response Mechanism

### 1. **Future-Based Coordination**
Each caller receives a `CompletableFuture` that will be completed when their specific file is processed:

```java
// In REST controller
CompletableFuture<FileClassificationResult> future = fileBatchingService.processFile(file);
FileClassificationResult result = future.get(300, TimeUnit.SECONDS); // Wait for result
```

### 2. **Result Distribution**
After batch processing, results are distributed back to individual callers:

```java
if ("FINISHED".equals(status.state())) {
    List<String> tags = dxrClient.getTagIds(status.datasourceScanId());
    for (int i = 0; i < batch.size() && i < fileDataList.size(); i++) {
        FileRequest request = batch.get(i);
        FileData fileData = fileDataList.get(i);
        FileClassificationResult result = new FileClassificationResult(
            fileData.filename(),
            "FINISHED",
            tags
        );
        request.result().complete(result); // Complete the individual future
    }
}
```

## Thread Safety

### 1. **Synchronized Access**
- All batch modifications are protected by `synchronized (batchLock)`
- Ensures atomic operations on batch state

### 2. **Thread-Safe Collections**
- `Collections.synchronizedList()` provides thread-safe list operations
- Additional synchronization on critical sections

### 3. **Executor Services**
- `CachedThreadPool` for batch processing (scales as needed)
- `ScheduledThreadPool` for timer management (single thread)

## Performance Benefits

### 1. **Efficient API Usage**
- Reduces API calls to Data X-Ray by batching multiple files
- Maintains optimal throughput while providing individual responses

### 2. **Resource Management**
- Datasource round-robin prevents overloading single datasources
- Configurable batch sizes prevent memory issues

### 3. **Responsive API**
- Immediate return of futures allows clients to wait asynchronously
- Configurable timeouts prevent hanging requests

## Key Design Decisions

### 1. **Why CompletableFuture?**
- Provides non-blocking asynchronous programming model
- Allows callers to wait for results without blocking server threads
- Enables timeout handling and cancellation

### 2. **Why Synchronized Blocks Instead of Locks?**
- Simpler code with automatic lock release
- Sufficient for the relatively short critical sections
- Reduces complexity and potential for deadlocks

### 3. **Why Separate Executor Services?**
- `ScheduledExecutorService` for precise timing control
- `CachedThreadPool` for dynamic scaling based on batch processing load
- Separation of concerns between timing and processing

## Configuration Trade-offs

### Batch Size (`DXR_MAX_BATCH_SIZE`)
- **Larger values**: Better API efficiency, higher memory usage, longer wait times
- **Smaller values**: Lower latency, more API calls, lower memory usage

### Batch Interval (`DXR_BATCH_INTERVAL_SEC`)
- **Longer intervals**: Better batching efficiency, higher individual request latency
- **Shorter intervals**: Lower latency, potentially less efficient batching

### Optimal Configuration
The optimal configuration depends on:
- Expected request volume and patterns
- Acceptable latency for individual requests
- Data X-Ray API rate limits and performance characteristics
- Available server resources

This design allows the server to handle high-concurrency scenarios efficiently while maintaining the synchronous API contract that clients expect.