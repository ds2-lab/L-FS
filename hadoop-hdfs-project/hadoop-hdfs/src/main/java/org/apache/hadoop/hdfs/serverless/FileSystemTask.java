package org.apache.hadoop.hdfs.serverless;

import com.google.common.base.Throwables;
import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.Serializable;
import java.util.concurrent.*;

/**
 * Encapsulates a user-specified File System operation to be executed by the NameNode.
 *
 * This is used to avoid duplicate requests from being processed when clients issue concurrent TCP and HTTP requests.
 */
public class FileSystemTask<T extends Serializable> implements Future<T> {
    private static final Log LOG = LogFactory.getLog(FileSystemTask.class);

    private static enum State {WAITING, DONE, CANCELLED, ERROR}

    private volatile State state = State.WAITING;

    /**
     * The unique ID identifying this future.
     */
    private final String taskId;

    /**
     * The name of the operation that will be performed to fulfill this future.
     */
    private final String operationName;

    /**
     * The arguments to be passed to the associated file system operation.
     */
    private final JsonObject operationArguments;

    /**
     * Return value of System.nanoTime() called in the constructor of this instance.
     */
    private final long createdAt;

    /**
     * This is used to receive the result of the future from the worker thread.
     */
    private final BlockingQueue<T> resultQueue = new ArrayBlockingQueue<>(1);

    public FileSystemTask(String taskId, String operationName, JsonObject operationArguments) {
        this.taskId = taskId;
        this.operationName = operationName;
        this.operationArguments = operationArguments;

        this.createdAt = System.nanoTime();
    }

    @Override
    public synchronized boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public synchronized boolean isCancelled() {
        return state == State.CANCELLED;
    }

    @Override
    public synchronized boolean isDone() {
        return state == State.DONE;
    }

    @Override
    public synchronized T get() throws InterruptedException, ExecutionException {
        return this.resultQueue.take();
    }

    @Override
    public synchronized T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        final T resultOrNull = this.resultQueue.poll(timeout, unit);
        if (resultOrNull == null) {
            throw new TimeoutException();
        }
        return resultOrNull;
    }

    /**
     * Post a result to this future so that it may be consumed by whoever is waiting on it.
     */
    public void postResult(T result) {
        try {
            resultQueue.put(result);
            this.state = State.DONE;
        }
        catch (Exception ex) {
            LOG.error("Exception encountered while attempting to put result into future: ", ex);
            this.state = State.ERROR;
        }
    }

    /**
     * Returns the amount of time (in milliseconds) that has elapsed since this task was created.
     * @return the amount of time, in milliseconds, that has elapsed since this task was created.
     */
    public long getTimeElapsedSinceCreation() {
        long timeElapsedNano = System.nanoTime() - createdAt;

        return TimeUnit.NANOSECONDS.toMillis(timeElapsedNano);
    }

    public long getCreatedAt() { return createdAt; }

    public JsonObject getOperationArguments() {
        return operationArguments;
    }

    public String getOperationName() {
        return operationName;
    }

    public String getTaskId() {
        return taskId;
    }
}
