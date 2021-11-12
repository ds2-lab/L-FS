package org.apache.hadoop.hdfs.serverless.zookeeper;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.nodes.GroupMember;
import org.apache.curator.framework.recipes.watch.PersistentWatcher;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.async.AsyncCuratorFramework;
import org.apache.hadoop.util.hash.Hash;
import org.apache.zookeeper.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Encapsulates ZooKeeper/Apache Curator Framework functionality for the NameNode.
 */
public class SyncZKClient implements ZKClient {
    public static final Log LOG = LogFactory.getLog(SyncZKClient.class);

    /**
     * Encapsulates a connection to the ZooKeeper ensemble.
     */
    private CuratorFramework client;

    /**
     * The hostnames to try connecting to.
     */
    private final String[] hosts;

    /**
     * The connection string used to connect to the ZooKeeper ensemble. Constructed from the
     * {@link SyncZKClient#hosts} instance variable.
     */
    private final String connectionString;

    /**
     * Unique ID identifying this member in its ZK group.
     */
    private final String memberId;

    /**
     * GroupMember instance for this client.
     *
     * You can get a current view of the members by calling:
     *      groupMember.getCurrentMembers();
     */
    private GroupMember groupMember;

    /**
     * Keep track of all our watchers. Generally there should just be one (for the group we're in).
     */
    private final HashMap<String, PersistentWatcher> watchers;

    /**
     * Constructor.
     * @param hosts Hostnames of the ZooKeeper servers.
     * @param memberId Unique ID identifying this member in its ZK group.
     */
    public SyncZKClient(String[] hosts, String memberId) {
        if (hosts == null)
            throw new IllegalArgumentException("The 'hosts' array argument must be non-null.");

        if (hosts.length == 0)
            throw new IllegalArgumentException("The 'hosts' array argument must have length greater than zero.");

        this.hosts = hosts;

        StringBuilder connectionStringBuilder = new StringBuilder();
        for (int i = 0; i < this.hosts.length; i++) {
            String host = this.hosts[i];

            connectionStringBuilder.append(host);

            if (i < this.hosts.length - 1)
                connectionStringBuilder.append(',');
        }
        this.connectionString = connectionStringBuilder.toString();

        this.memberId = memberId;

        this.watchers = new HashMap<>();
    }

    /**
     * Return a flag indicating whether we're currently connected to ZooKeeper.
     */
    public boolean verifyConnection() {
        return this.client.getZookeeperClient().isConnected();
    }

    /**
     * Connect to the ZooKeeper ensemble.
     *
     * @return A {@link ZooKeeper object} representing the connection to the server/ensemble.
     */
    private CuratorFramework connectToZooKeeper() {
        // These are reasonable arguments for the ExponentialBackoffRetry. The first
        // retry will wait 1 second - the second will wait up to 2 seconds - the
        // third will wait up to 4 seconds.
        // TODO: Make these configurable?
        ExponentialBackoffRetry retryPolicy = new ExponentialBackoffRetry(1000, 5);

        return CuratorFrameworkFactory.newClient(connectionString, retryPolicy);
    }

    @Override
    public void connect() {
        LOG.debug("Connecting to the ZK ensemble now...");
        this.client = connectToZooKeeper();
        LOG.debug("Connected successfully to ZK ensemble. Starting ZK client now...");
        this.client.start();
        LOG.debug("Successfully started ZK client.");

        AsyncCuratorFramework asyncClient = AsyncCuratorFramework.wrap(client);
    }

    @Override
    public void createGroup(String groupName) throws Exception, KeeperException.NodeExistsException {
        if (this.client == null)
            throw new IllegalStateException("ZooKeeper client must be instantiated before joining a group.");

        if (groupName == null)
            throw new IllegalArgumentException("Group name parameter must be non-null.");

        String path = "/" + groupName; // The paths must be fully-qualified, so we prepend an '/'.

        LOG.debug("Creating ZK group with path: " + path);

        // This will throw an exception if the group already exists!
        this.client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
    }

    @Override
    public void joinGroup(String groupName, String memberId, Invalidatable invalidatable) throws Exception {
        if (this.client == null)
            throw new IllegalStateException("ZooKeeper client must be instantiated before joining a group.");

        if (groupName == null)
            throw new IllegalArgumentException("Group name parameter must be non-null.");

        String path = "/" + groupName + "/" + memberId; // The paths must be fully-qualified, so we prepend an '/'.

        LOG.debug("Joining ZK group with path: " + path);
        this.client.create().withMode(CreateMode.EPHEMERAL).forPath(path);

        PersistentWatcher persistentWatcher = new PersistentWatcher(this.client, path, false);
        persistentWatcher.start();

        if (this.watchers.containsKey(path))
            LOG.warn("We already have a watcher for path " + path + ".");
        else
            this.watchers.put(path, persistentWatcher);

        // We need to invalidate our cache whenever our connection to ZooKeeper is lost.
        client.getConnectionStateListenable().addListener((curatorFramework, connectionState) -> {
            if (!connectionState.isConnected()) {
                LOG.warn("Connection to ZooKeeper lost. Need to invalidate cache.");
                invalidatable.invalidateCache();
            } else {
                LOG.debug("Connected established with ZooKeeper ensemble.");
                // TODO: If our connection is automatically re-established, do we need to re-create our ZNode?
                //       Or is that handled for us?
            }
        });
    }

    @Override
    public void close() {
        LOG.debug("Closing SyncZKClient now...");

        if (this.groupMember != null)
            this.groupMember.close();
    }

    @Override
    public void createAndJoinGroup(String groupName, String memberId, Invalidatable invalidatable) throws Exception {
        try {
            // This will throw an exception if the group already exists!
            createGroup(groupName);
            LOG.debug("Successfully created new ZooKeeper group '/" + groupName + "'.");
        } catch (KeeperException.NodeExistsException ex) {
            LOG.debug("ZooKeeper group '/" + groupName + "' already exists.");
        }

        joinGroup(groupName, memberId, invalidatable);
    }

//    public List<String> getGroupMembers(String groupName, Runnable callback) throws Exception {
//        if (groupName == null)
//            throw new IllegalArgumentException("Group name argument cannot be null.");
//
//        if (callback == null)
//            throw new IllegalArgumentException("Callback argument cannot be null.");
//
//        String path = "/" + groupName;
//
//        LOG.debug("Getting children for group: " + path);
//        List<String> children = this.client.getChildren().usingWatcher(new Watcher() {
//            @Override
//            public void process(WatchedEvent event) {
//                LOG.debug("Watcher received event " + event.getType().name() + " for children of group: " + path);
//
//                // We only care about this event if it is about the children of the group changing.
//                if (event.getType() == Event.EventType.NodeChildrenChanged) {
//                    try {
//                        LOG.debug("Executing callback for NodeChildrenChanged event on group " + path + " now...");
//                        callback.run();
//                    } catch (Exception ex) {
//                        LOG.error("Error encountered while executing callback:", ex);
//                    }
//                }
//            }
//        }).forPath(path);
//
//        if (children.isEmpty())
//            LOG.warn("There are no children in group: " + path);
//
//        return children;
//    }

    @Override
    public void addListener(String groupName, Watcher watcher) {
        String path = "/" + groupName;

        PersistentWatcher persistentWatcher = watchers.getOrDefault(path, null);
        if (persistentWatcher == null) {
            LOG.error("Tried to get non-existent watcher for path " + groupName + ".");
            LOG.error("Valid watchers: " + watchers.keySet());
            throw new IllegalArgumentException("We do not have a PersistentWatcher for the path: " + path);
        }

        persistentWatcher.getListenable().addListener(watcher);
    }

    @Override
    public void removeListener(String groupName, Watcher watcher) {
        String path = "/" + groupName;

        PersistentWatcher persistentWatcher = watchers.getOrDefault(path, null);
        if (persistentWatcher == null) {
            LOG.error("Tried to get non-existent watcher for path " + groupName + ".");
            LOG.error("Valid watchers: " + watchers.keySet());
            throw new IllegalArgumentException("We do not have a PersistentWatcher for the path: " + path);
        }

        persistentWatcher.getListenable().removeListener(watcher);
    }

    @Override
    public List<String> getGroupMembers(String groupName) throws Exception {
        if (groupName == null)
            throw new IllegalArgumentException("Group name argument cannot be null.");

        String path = "/" + groupName;

        LOG.debug("Getting children for group: " + path);
        List<String> children = this.client.getChildren().forPath(path);

        if (children.isEmpty())
            LOG.warn("There are no children in group: " + path);

        return children;
    }

//    public Map<String, byte[]> getGroupMembers() {
//        if (this.groupMember == null)
//            throw new IllegalStateException("Must first join a group before retrieving group members.");
//
//        return this.groupMember.getCurrentMembers();
//    }

//    @Override
//    public void createAndJoinGroup(String groupName) {
//        String path = "/" + groupName; // The paths must be fully-qualified, so we prepend an '/'.
//
//        LOG.debug("Joining ZK group via GroupMember API with path: " + path);
//        this.groupMember = new GroupMember(this.client, path, this.memberId, new byte[0]);
//        this.groupMember.start();
//    }

//    @Override
//    public GroupMember getGroupMember() {
//        return this.groupMember;
//    }

//    @Override
//    public <T> void createWatch(String groupName, Callable<T> callback) {
//        if (this.client == null)
//            throw new IllegalStateException("Client must be created/instantiated before any watches can be created.");
//        if (groupName == null)
//            throw new IllegalArgumentException("Group name argument cannot be null.");
//
//        String path = "/" + groupName;
//
//        LOG.debug("Synchronously creating watch for path: " + path);
//    }
}
