package org.apache.hadoop.hdfs.serverless.tcpserver;

import com.esotericsoftware.kryo.Kryo;
import io.hops.metrics.TransactionAttempt;
import io.hops.metrics.TransactionEvent;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.protocol.DirectoryListing;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LastBlockWithStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.serverless.operation.ActiveServerlessNameNode;
import org.apache.hadoop.hdfs.serverless.operation.ActiveServerlessNameNodeList;
import org.apache.hadoop.hdfs.serverless.operation.execution.DuplicateRequest;
import org.apache.hadoop.hdfs.serverless.operation.execution.NameNodeResult;
import org.apache.hadoop.hdfs.serverless.operation.execution.NullResult;

/**
 * Utility functions exposed by both TCP clients and servers.
 */
public class ServerlessClientServerUtilities {
    private static final Log LOG = LogFactory.getLog(ServerlessClientServerUtilities.class);

    /**
     * This operation is used when a NameNode is first connecting to and registering with a client.
     */
    public static final String OPERATION_REGISTER = "REGISTER";

    /**
     * This operation is used when a NameNode is returning the result of some FS operation back to a client.
     */
    public static final String OPERATION_RESULT = "RESULT";

    /**
     * This operation is used when the NameNode just wants to report some information to the client.
     *
     * As of right now, this information will just be logged/use for debugging purposes.
     */
    public static final String OPERATION_INFO = "INFO";

    /**
     * Register all the classes that are going to be sent over the network.
     *
     * This must be done on both the client and the server before any network communication occurs.
     * The exact same classes are to be registered in the exact same order.
     * @param kryo The Kryo object obtained from a given Kryo TCP client/server via getKryo().
     */
    public static synchronized void registerClassesToBeTransferred(Kryo kryo) {
        kryo.register(DuplicateRequest.class);
        kryo.register(NullResult.class);
        kryo.register(NameNodeResult.class);
        kryo.register(NameNodeResult.ServerlessFunctionMapping.class);
        kryo.register(TransactionEvent.class);
        kryo.register(TransactionAttempt.class);
        kryo.register(LocatedBlocks.class);
        kryo.register(NamespaceInfo.class);
        kryo.register(LastBlockWithStatus.class);
        kryo.register(HdfsFileStatus.class);
        kryo.register(HdfsFileStatus[].class);
        kryo.register(DirectoryListing.class);
        kryo.register(FsServerDefaults.class);
        kryo.register(ActiveServerlessNameNodeList.class);
        kryo.register(ActiveServerlessNameNode.class);
        kryo.register(Throwable.class);
        kryo.register(java.util.HashMap.class);
        kryo.register(java.util.ArrayList.class);
        kryo.register(byte[].class);
        kryo.register(FsPermission.class);
        kryo.register(FsAction.class);
    }
}
