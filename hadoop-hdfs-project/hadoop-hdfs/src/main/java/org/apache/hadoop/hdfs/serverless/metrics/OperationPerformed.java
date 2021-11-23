package org.apache.hadoop.hdfs.serverless.metrics;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

// "Op Name", "Start Time", "End Time", "Duration (ms)", "Deployment", "HTTP", "TCP"
public class OperationPerformed implements Serializable, Comparable<OperationPerformed> {
    /**
     * Compare OperationPerformed instances by their start time.
     */
    public static Comparator<OperationPerformed> BY_START_TIME = new Comparator<OperationPerformed>() {
        @Override
        public int compare(OperationPerformed o1, OperationPerformed o2) {
            return (int)(o1.startTime - o2.startTime);
        }
    };

    /**
     * Compare OperationPerformed instances by their end time.
     */
    public static Comparator<OperationPerformed> BY_END_TIME = new Comparator<OperationPerformed>() {
        @Override
        public int compare(OperationPerformed o1, OperationPerformed o2) {
            return (int)(o1.endTime - o2.endTime);
        }
    };

    /**
     * Number of INode cache hits that the NameNode encountered while processing the associated request.
     */
    private int metadataCacheHits;

    /**
     * Number of INode cache hits that the NameNode encountered while processing the associated request.
     */
    private int metadataCacheMisses;

    private static final long serialVersionUID = -3094538262184661023L;

    private final String operationName;

    private final String requestId;

    private final long startTime;

    private long endTime;

    private long duration;

    private final int deployment;

    private final boolean issuedViaTcp;

    private final boolean issuedViaHttp;

    private long nameNodeId;

    public OperationPerformed(String operationName, String requestId, long startTime, long endTime,
                              int deployment, boolean issuedViaHttp, boolean issuedViaTcp,
                              long nameNodeId, int metadataCacheMisses, int metadataCacheHits) {
        this.operationName = operationName;
        this.requestId = requestId;
        this.startTime = startTime / 1000000;
        this.endTime = endTime / 1000000;
        this.duration = endTime - startTime;
        this.deployment = deployment;
        this.issuedViaHttp = issuedViaHttp;
        this.issuedViaTcp = issuedViaTcp;
        this.nameNodeId = nameNodeId;
        this.metadataCacheHits = metadataCacheHits;
        this.metadataCacheMisses = metadataCacheMisses;
    }

    public void setNameNodeId(long nameNodeId) {
        this.nameNodeId = nameNodeId;
    }

    /**
     * Modify the endTime of this OperationPerformed instance.
     * This also recomputes this instance's `duration` field.
     *
     * @param endTime The end time in nanoSeconds.
     */
    public void setEndTime(long endTime) {
        this.endTime = endTime / 1000000;
        this.duration = this.endTime - startTime;
    }

    public Object[] getAsArray() {
        return new Object[] {
                this.operationName, this.startTime, this.endTime, this.duration, this.deployment,
                this.issuedViaHttp, this.issuedViaTcp
        };
    }

    @Override
    public String toString() {
        String format = "%-16s %-38s %-26s %-26s %-8s %-3s %-22s %-5s %-5s";

//        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd hh:mm:ss:SSS")
//                .withLocale( Locale.US )
//                .withZone( ZoneId.of("UTC"));

        // We divide duration by 10^6 bc right now it is in nanoseconds, and we want milliseconds.
        return String.format(format, operationName, requestId, Instant.ofEpochMilli(startTime).toString(),
                Instant.ofEpochMilli(endTime).toString(), (duration / 1000000.0), deployment, nameNodeId,
                metadataCacheHits, metadataCacheMisses);
                //(issuedViaHttp ? "HTTP" : "-"), (issuedViaTcp ? "TCP" : "-"));

//            return operationName + " \t" + Instant.ofEpochMilli(timeIssued).toString() + " \t" +
//                    (issuedViaHttp ? "HTTP" : "-") + " \t" + (issuedViaTcp ? "TCP" : "-");
    }

    /**
     * Compare two instances of OperationPerformed.
     * The comparison is based exclusively on their timeIssued field.
     */
    @Override
    public int compareTo(OperationPerformed op) {
        return Long.compare(endTime, op.endTime);
    }

    public String getRequestId() {
        return requestId;
    }

    public int getMetadataCacheMisses() {
        return metadataCacheMisses;
    }

    public void setMetadataCacheMisses(int metadataCacheMisses) {
        this.metadataCacheMisses = metadataCacheMisses;
    }

    public int getMetadataCacheHits() {
        return metadataCacheHits;
    }

    public void setMetadataCacheHits(int metadataCacheHits) {
        this.metadataCacheHits = metadataCacheHits;
    }
}
