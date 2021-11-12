/*
 * Copyright (C) 2015 hops.io.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hops.transaction.handler;

import io.hops.events.*;
import io.hops.exception.StorageException;
import io.hops.leader_election.node.ActiveNode;
import io.hops.metadata.HdfsStorageFactory;
import io.hops.metadata.hdfs.TablesDef;
import io.hops.metadata.hdfs.dal.InvalidationDataAccess;
import io.hops.metadata.hdfs.dal.WriteAcknowledgementDataAccess;
import io.hops.metadata.hdfs.entity.Invalidation;
import io.hops.metadata.hdfs.entity.WriteAcknowledgement;
import io.hops.transaction.EntityManager;
import io.hops.transaction.TransactionInfo;
import io.hops.transaction.context.EntityContext;
import io.hops.transaction.context.INodeContext;
import io.hops.transaction.lock.HdfsTransactionalLockAcquirer;
import io.hops.transaction.lock.TransactionLockAcquirer;
import org.apache.hadoop.hdfs.protocol.RecoveryInProgressException;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.ServerlessNameNode;
import org.apache.hadoop.hdfs.serverless.OpenWhiskHandler;
import org.apache.hadoop.hdfs.serverless.zookeeper.ZKClient;
import org.apache.zookeeper.Watcher;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public abstract class HopsTransactionalRequestHandler
        extends TransactionalRequestHandler implements HopsEventListener {

  private final String path;

  /**
   * Used to keep track of whether an ACK has been received from each follower NN during the consistency protocol.
   */
  private HashSet<Long> waitingForAcks = new HashSet<>();

  /**
   * Used as a unique identifier for the operation. This is only used during write operations.
   */
  private final long operationId;

  /**
   * Used to access the serverless name node instance in the NDB event handler.
   */
  private ServerlessNameNode serverlessNameNodeInstance;

  /**
   * We use this CountDownLatch when waiting on ACKs and watching for changes in membership. Specifically,
   * each time we receive an ACK, the latch is decremented, and if any follower NNs leave the group during
   * this operation, the latch is also decremented. Thus, we are eventually woken up when the CountDownLatch
   * reaches zero.
   */
  private CountDownLatch countDownLatch;

  public HopsTransactionalRequestHandler(HDFSOperationType opType) {
    this(opType, null);
  }
  
  public HopsTransactionalRequestHandler(HDFSOperationType opType, String path) {
    super(opType);
    this.path = path;
    this.operationId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
  }

  @Override
  protected TransactionLockAcquirer newLockAcquirer() {
    return new HdfsTransactionalLockAcquirer();
  }

//  @Override
//  protected void checkAndHandleNewConcurrentWrites(long txStartTime) throws StorageException {
//    requestHandlerLOG.debug("Checking for concurrent write operations that began after this local one.");
//    WriteAcknowledgementDataAccess<WriteAcknowledgement> writeAcknowledgementDataAccess =
//            (WriteAcknowledgementDataAccess<WriteAcknowledgement>) HdfsStorageFactory.getDataAccess(WriteAcknowledgementDataAccess.class);
//
//    Map<Long, WriteAcknowledgement> mapping =
//            writeAcknowledgementDataAccess.checkForPendingAcks(serverlessNameNodeInstance.getId(), txStartTime);
//
//    if (mapping.size() > 0) {
//      if (mapping.size() == 1)
//        requestHandlerLOG.debug("There is 1 pending ACK for a write operation that began after this local one.");
//      else
//        requestHandlerLOG.debug("There are " + mapping.size() +
//                " pending ACKs for write operation(s) that began after this local one.");
//
//      // Build up a list of INodes (by their IDs) that we need to keep invalidated. They must remain invalidated
//      // because there are future write operations that are writing to them, so we don't want to set their valid
//      // flags to 'true' after this.
//      //
//      // Likewise, any INodes NOT in this list should be set to valid. Consider a scenario where we are the latest
//      // write operation in a series of concurrent/overlapping write operations. In this scenario, the INodes that we
//      // are modifying presumably already had their `INV` flags set to True. As I'm writing this, I'm not entirely sure
//      // if it's possible for us to get a local copy of an INode with an `INV` bit set to true, since eventually read
//      // operations will block and not return INodes with an `INV` column value of true. But in any case, we need to
//      // make sure the INodes we're modifying are valid after this. We locked the rows, so nobody else can change them.
//      // If another write comes along and invalidates them immediately, that's fine. But if we don't ensure they're all
//      // set to valid, then reads may continue to block indefinitely.
//      List<Long> nodesToKeepInvalidated = new ArrayList<Long>();
//      for (Map.Entry<Long, WriteAcknowledgement> entry : mapping.entrySet()) {
//        long operationId = entry.getKey();
//        WriteAcknowledgement ack = entry.getValue();
//
//        requestHandlerLOG.debug("   Operation ID: " + operationId + ", ACK: " + ack.toString());
//
//        nodesToKeepInvalidated.add();
//      }
//    }
//  }
  
  @Override
  protected Object execute(final Object namesystem) throws IOException {
//    if (opType.shouldUseConsistencyProtocol()) {
//      requestHandlerLOG.debug("Transaction type <" + opType.getName()
//              + "> *SHOULD* use the serverless consistency protocol.");
//    } else {
//      requestHandlerLOG.debug("Transaction type <" + opType.getName()
//              + "> does NOT need to use the serverless consistency protocol.");
//    }
//    requestHandlerLOG.debug("Transaction is operating on path: " + path);
    if (namesystem instanceof FSNamesystem) {
      FSNamesystem namesystemInst = (FSNamesystem)namesystem;
      List<ActiveNode> activeNodes = namesystemInst.getActiveNameNodesInDeployment();
      requestHandlerLOG.debug("Active nodes: " + activeNodes.toString());
    } else if (namesystem == null) {
      requestHandlerLOG.debug("Transaction namesystem object is null! Cannot determine active nodes.");
    } else {
      requestHandlerLOG.debug("Transaction namesystem object is of type " + namesystem.getClass().getSimpleName());
    }

    return super.execute(new TransactionInfo() {
      @Override
      public String getContextName(OperationType opType) {
        if (namesystem instanceof FSNamesystem) {
          return "NN (" + ((FSNamesystem) namesystem).getNamenodeId() + ") " +
              opType.toString() + "[" + Thread.currentThread().getId() + "]";
        } else {
          return opType.toString();
        }
      }

      @Override
      public void performPostTransactionAction() throws IOException {
        if (namesystem instanceof FSNamesystem) {
          ((FSNamesystem) namesystem).performPendingSafeModeOperation();
        }
      }
    });
  }

  @Override
  protected final void preTransactionSetup() throws IOException {
    setUp();
  }

  @Override
  protected final boolean consistencyProtocol(long txStartTime) throws IOException {
    EntityContext<?> inodeContext = EntityManager.getEntityContext(INode.class);
    return doConsistencyProtocol(inodeContext, txStartTime);
  }

  /**
   * This function should be overridden in order to provide a consistency protocol whenever necessary.
   *
   * @param entityContext Must be an INodeContext object. Used to determine which INodes are being written to.
   * @param txStartTime The time at which the transaction began. Used to order operations.
   *
   * @return True if the transaction can safely proceed, otherwise false.
   */
  public boolean doConsistencyProtocol(EntityContext<?> entityContext, long txStartTime) throws IOException {
    //// // // // // // // // // // ////
    // CURRENT CONSISTENCY PROTOCOL   //
    //// // // // // // // // // // ////
    //
    // TERMINOLOGY:
    // - Leader NameNode: The NameNode performing the write operation.
    // - Follower NameNode: NameNode instance from the same deployment as the Leader NameNode.
    //
    // The updated consistency protocol for Serverless NameNodes is as follows:
    // (1) The Leader NN adds N-1 un-ACK'd records to the "ACKs" table of the Leader's deployment, where N is the
    //     number of nodes in the Leader's deployment. (N-1 as it does not need to add a record for itself.)
    // (2) The Leader NN begins listening for changes in group membership from ZooKeeper.
    //     The Leader will also subscribe to events on the ACKs table for reasons that will be made clear shortly. We
    //     need to subscribe first to ensure we receive notifications from follower NNs ACK'ing the entries.
    // (3) The leader issues one INV per modified INode to the target deployment's INV table.
    // (4) Follower NNs will ACK their entry in the ACKs table upon receiving the INV from intermediate storage (NDB).
    //     The follower will also invalidate its cache at this point, thereby readying itself for the upcoming write.
    // (5) The Leader listens for updates on the ACK table, waiting for all entries to be ACK'd.
    //     If there are any NN failures during this phase, the Leader will detect them via ZK. The Leader does not
    //     need ACKs from failed NNs, as they invalidate their cache upon returning.
    // (6) Once all the "ACK" table entries added by the Leader have been ACK'd by followers, the Leader will check to
    //     see if there are any new, concurrent write operations with a larger timestamp. If so, the Leader must
    //     first finish its own write operation BEFORE submitting any ACKs for those new writes. Then, the leader can
    //     ACK any new write operations that may be waiting.
    //
    //// // // // // // // // // // //// // // // // // // // // // //// // // // // // // // // // ////

    if (!(entityContext instanceof INodeContext))
      throw new IllegalArgumentException("Consistency protocol requires an instance of INodeContext. " +
              "Instead, received " +
              ((entityContext == null) ? "null." : "instance of " +
                      entityContext.getClass().getSimpleName() + "."));

    INodeContext transactionINodeContext = (INodeContext)entityContext;

    Collection<INode> invalidatedINodes = transactionINodeContext.getInvalidatedINodes();
    int numInvalidated = invalidatedINodes.size();

    // If there are no invalidated INodes, then we do not need to carry out the consistency protocol;
    // however, if there is at least 1 invalidated INode, then we must proceed with the protocol.
    if (numInvalidated == 0)
      return true;

    requestHandlerLOG.debug("=-=-=-=-= CONSISTENCY PROTOCOL =-=-=-=-=");
    requestHandlerLOG.debug("Operation ID: " + operationId);
    requestHandlerLOG.debug("Operation Start Time: " + txStartTime);
    ServerlessNameNode serverlessNameNode = OpenWhiskHandler.instance;

    // Sanity check. Make sure we have a valid reference to the ServerlessNameNode. This isn't the cleanest, but
    // with the way HopsFS has structured its code, this is workable for our purposes.
    if (serverlessNameNode == null)
      throw new IllegalStateException(
              "Somehow a Transaction is occurring when the static ServerlessNameNode instance is null.");

    serverlessNameNodeInstance = serverlessNameNode;

    // TODO: Implement subtree protocol. Required modifications for basic version involve
    //       having the Leader NN join the ZK groups of whatever deployments it is modifying
    //       and subscribing to ACK events on the tables for each of the other deployments.
    //       It's basically just the "guest NN optimization" (where NN joins single other deployment
    //       to help the other deployment serve reads), except here the NN joins possibly many other deployments.
    //       For now, subtree operations will produce errors/fail.

    // Sanity check. Make sure we're only modifying INodes that we are authorized to modify.
    // If we find that we are about to modify an INode for which we are not authorized, throw an exception.
    for (INode invalidatedINode : invalidatedINodes) {
      int mappedDeploymentNumber = serverlessNameNode.getMappedServerlessFunction(invalidatedINode);
      int localDeploymentNumber = serverlessNameNode.getDeploymentNumber();

      if (mappedDeploymentNumber != localDeploymentNumber) {
        requestHandlerLOG.error("Transaction intends to update INode " + invalidatedINode.getFullPathName()
                + ", however only NameNodes from deployment #" + mappedDeploymentNumber
                + " should be modifying this INode. We are from deployment #" + localDeploymentNumber);
        throw new IOException("Modification of INode " + invalidatedINode.getFullPathName()
                + " is unauthorized for NameNodes from deployment #" + localDeploymentNumber
                + "; only NameNodes from deployment #" + mappedDeploymentNumber + " may modify this INode.");
      } else {
        requestHandlerLOG.debug("Modification of INode " + invalidatedINode.getFullPathName() + " is permitted.");
      }
    }

    requestHandlerLOG.debug("Leader NameNode: " + serverlessNameNode.getFunctionName() + ", ID = "
            + serverlessNameNode.getId() + ", Follower NameNodes: "
            + serverlessNameNode.getActiveNameNodes().getActiveNodes().toString() + ".");

    // Technically this isn't true yet, but we'll need to unsubscribe after the call to `subscribeToAckEvents()`.
    boolean needToUnsubscribe = true;

    // ======================================
    // === EXECUTING CONSISTENCY PROTOCOL ===
    // ======================================
    //
    //
    // =============== STEP 1 ===============
    List<WriteAcknowledgement> writeAcknowledgements;
    try {
      writeAcknowledgements = addAckTableRecords(serverlessNameNode, txStartTime);
    } catch (Exception ex) {
      requestHandlerLOG.error("Exception encountered on Step 3 of consistency protocol (adding ACKs to table).");
      ex.printStackTrace();
      return false;
    }

    // =============== STEP 2 ===============
    subscribeToAckEvents(serverlessNameNode);

    // =============== STEP 3 ===============
    issueInitialInvalidations(invalidatedINodes, serverlessNameNode, txStartTime);

    // If it turns out there are no other active NNs in our deployment, then we can just unsubscribe right away.
    if (writeAcknowledgements.size() == 0) {
      requestHandlerLOG.debug("We're the only active NN in our deployment. Unsubscribing from ACK events now.");
      unsubscribeFromAckEvents(serverlessNameNode);
      needToUnsubscribe = false;
    }


    try {
      // STEP 4 & 5
      waitForAcks(serverlessNameNode);
    } catch (Exception ex) {
      requestHandlerLOG.error("Exception encountered on Step 4 and 5 of consistency protocol (waiting for ACKs).");
      requestHandlerLOG.error("We're still waiting on " + waitingForAcks.size() +
              " ACKs from the following NameNodes: " + waitingForAcks);
      ex.printStackTrace();
      return false;
    }

    // Clean up ACKs, event operation, etc.
    cleanUpAfterConsistencyProtocol(serverlessNameNode, needToUnsubscribe, writeAcknowledgements);

    // Steps 6 and 7 happen automatically. We can return from this function to perform the writes.
    return true;
  }

  /**
   * This is used as a listener for ZooKeeper events during the consistency protocol. This updates the
   * datastructures tracking the ACKs we're waiting on in response to follower NNs dropping out during the
   * consistency protocol.
   *
   * This function is called once AFTER being set as the event listener to ensure no membership changes occurred
   * between when the leader NN first checked group membership to create the ACK entries and when the leader begins
   * monitoring explicitly for changes in group membership.
   */
  private synchronized void checkAndProcessMembershipChanges(ServerlessNameNode serverlessNameNode)
          throws Exception {
    ZKClient zkClient = serverlessNameNode.getZooKeeperClient();

    // Get the current members.
    List<String> groupMemberIdsAsStrings = zkClient.getGroupMembers(serverlessNameNode.getFunctionName());

    // Convert from strings to longs.
    List<Long> groupMemberIds = groupMemberIdsAsStrings.stream()
            .mapToLong(Long::parseLong)
            .boxed()
            .collect(Collectors.toList());

    // For each NN that we're waiting on, check that it is still a member of the group. If it is not, then remove it.
    List<Long> removeMe = new ArrayList<>();
    for (long memberId : waitingForAcks) {
      if (!groupMemberIds.contains(memberId))
        removeMe.add(memberId);
    }

    // Stop waiting on any NNs that have failed since the consistency protocol began.
    if (removeMe.size() > 0) {
      requestHandlerLOG.warn("Found " + removeMe.size()
              + " NameNode(s) that we are waiting on, but are no longer part of the group.");
      requestHandlerLOG.warn("IDs of these NameNodes: " + removeMe);
      removeMe.forEach(s -> {
        waitingForAcks.remove(s);   // Remove from the set of ACKs we're still waiting on.
        countDownLatch.countDown(); // Decrement the count-down latch once for each entry we remove.
      });
    }

    // If after removing all the failed follower NNs, we are not waiting on anybody, then we can just return.
    if (removeMe.size() > 0 && waitingForAcks.size() == 0) {
      requestHandlerLOG.debug("After removal of " + removeMe.size() +
              " failed follower NameNode(s), we have all required ACKs.");
    } else if (removeMe.size() > 0) {
      requestHandlerLOG.debug("After removal of " + removeMe.size() +
              "failed follower NameNode(s), we are still waiting on " + waitingForAcks.size() +
              " more ACK(s) from " + waitingForAcks + ".");
    } else {
      requestHandlerLOG.debug("We did not remove any NameNodes from our ACK list. Still waiting on " +
              waitingForAcks.size() + " ACK(s) from " + waitingForAcks + ".");
    }
  }

  /**
   * This function performs steps 4 and 5 of the consistency protocol. We, as the leader, simply have to wait for the
   * follower NNs to ACK our write operations.
   */
  private void waitForAcks(ServerlessNameNode serverlessNameNode) throws Exception {
    requestHandlerLOG.debug("=-----=-----= Steps 4 & 5 - Adding ACK Records =-----=-----=");

    ZKClient zkClient = serverlessNameNode.getZooKeeperClient();

    // Start listening for changes in group membership.
    zkClient.addListener(serverlessNameNode.getFunctionName(), watchedEvent -> {
      if (watchedEvent.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
        try {
          checkAndProcessMembershipChanges(serverlessNameNode);
        } catch (Exception e) {
          requestHandlerLOG.error("Encountered error while reacting to ZooKeeper event.");
          e.printStackTrace();
        }
      }
    });

    // This method is 'synchronized' so if the event handler already fired, we won't be able to get inside
    // until after the event handler finishes. Shouldn't cause any concurrency issues...
    checkAndProcessMembershipChanges(serverlessNameNode);

    // Wait until we're done. If the latch is already at zero, then this will not block.
    countDownLatch.await();
    requestHandlerLOG.debug("We have received all required ACKs for write operation " + operationId + ".");
  }

  /**
   * Perform any necessary clean-up steps after the consistency protocol has completed.
   * This includes unsubscribing from ACK table events, removing the ACK entries from the table in NDB, etc.
   */
  private void cleanUpAfterConsistencyProtocol(ServerlessNameNode serverlessNameNode, boolean needToUnsubscribe,
                                               Collection<WriteAcknowledgement> writeAcknowledgements)
          throws StorageException {
    // Unsubscribe and unregister event listener if we haven't done so already. (If we were the only active NN in
    // our deployment at the beginning of the protocol, then we would have already unsubscribed by this point.)
    if (needToUnsubscribe)
      unsubscribeFromAckEvents(serverlessNameNode);

    // Remove the ACK entries that we added.
    WriteAcknowledgementDataAccess<WriteAcknowledgement> writeAcknowledgementDataAccess =
            (WriteAcknowledgementDataAccess<WriteAcknowledgement>) HdfsStorageFactory.getDataAccess(WriteAcknowledgementDataAccess.class);
    writeAcknowledgementDataAccess.deleteAcknowledgements(
            writeAcknowledgements, serverlessNameNode.getDeploymentNumber());
  }

  /**
   * Unregister ourselves as an event listener for ACK table events, then unregister the event operation itself.
   */
  private void unsubscribeFromAckEvents(ServerlessNameNode serverlessNameNode) throws StorageException {
    String eventName = HopsEvent.ACK_EVENT_NAME_BASE + serverlessNameNode.getDeploymentNumber();
    EventManager eventManager = serverlessNameNode.getNdbEventManager();
    eventManager.removeListener(this, eventName);
    eventManager.unregisterEventOperation(eventName);
  }

  @Override
  public void eventReceived(HopsEventOperation eventData, String eventName) {
    if (!eventName.equals(HopsEvent.ACK_EVENT_NAME_BASE))
      requestHandlerLOG.debug("HopsTransactionalRequestHandler received unexpected event " + eventName + "!");

    // First, verify that this event pertains to our write operation. If it doesn't, we just return.
    long writeOpId = eventData.getLongPostValue(TablesDef.WriteAcknowledgementsTableDef.OPERATION_ID);
    long nameNodeId = eventData.getLongPostValue(TablesDef.WriteAcknowledgementsTableDef.NAME_NODE_ID);
    if (writeOpId != operationId && nameNodeId != serverlessNameNodeInstance.getId())
      return;

    String eventType = eventData.getEventType();
    if (eventType.equals(HopsEventType.INSERT)) // We don't care about INSERT events.
      return;

    boolean acknowledged = eventData.getBooleanPostValue(TablesDef.WriteAcknowledgementsTableDef.ACKNOWLEDGED);

    if (acknowledged) {
      requestHandlerLOG.debug("Received ACK from NameNode " + nameNodeId + "!");

      // If we're receiving an ACK for this NameNode, then it better be the case that
      // we're waiting on it. Otherwise, something is wrong.
      if (!waitingForAcks.contains(nameNodeId))
        throw new IllegalStateException("We received an ACK from NN " + nameNodeId +
                ", but that NN is not in our 'waiting on' list. Size of list: " + waitingForAcks.size() + ".");

      waitingForAcks.remove(nameNodeId);

      countDownLatch.countDown();
    }
  }

  /**
   * Perform Step (2) of the consistency protocol:
   *    The Leader NN begins listening for changes in group membership from ZooKeeper.
   *    The Leader will also subscribe to events on the ACKs table for reasons that will be made clear shortly.
   */
  private void subscribeToAckEvents(ServerlessNameNode serverlessNameNode) throws StorageException {
    requestHandlerLOG.debug("=-----=-----= Step 2 - Subscribing to ACK Events =-----=-----=");

    String targetTableName;
    switch (serverlessNameNode.getDeploymentNumber()) {
      case 0:
        targetTableName = TablesDef.WriteAcknowledgementsTableDef.TABLE_NAME0;
        break;
      case 1:
        targetTableName = TablesDef.WriteAcknowledgementsTableDef.TABLE_NAME1;
        break;
      case 2:
        targetTableName = TablesDef.WriteAcknowledgementsTableDef.TABLE_NAME2;
        break;
      default:
        throw new StorageException("Unsupported deployment number: " + serverlessNameNode.getDeploymentNumber());
    }

    String eventName = HopsEvent.ACK_EVENT_NAME_BASE + serverlessNameNode.getDeploymentNumber();
    EventManager eventManager = serverlessNameNode.getNdbEventManager();
    boolean eventCreated = eventManager.registerEvent(eventName, targetTableName,
            eventManager.getAckTableEventColumns(), false);

    if (eventCreated)
      requestHandlerLOG.debug("Event " + eventName + " on table " + targetTableName + " created successfully.");
    else
      requestHandlerLOG.debug("Event " + eventName + " on table " + targetTableName +
              " already exists. Reusing existing event.");

    eventManager.createEventOperation(eventName);
    eventManager.addListener(this, eventName);
  }

  /**
   * Perform Step (1) of the consistency protocol:
   *    Add N-1 un-ACK'd records to the "ACKs" table, where N is the number of nodes in the Leader's deployment.
   *    We subscribe AFTER adding these entries just to avoid receiving events for inserting the new ACK entries, as
   *    we'd waste time processing those events (albeit a small amount of time).
   *
   * @param txStartTime The UTC timestamp at which this write operation began.
   *
   * @return The number of ACK records that we added to intermediate storage.
   */
  private List<WriteAcknowledgement> addAckTableRecords(ServerlessNameNode serverlessNameNode, long txStartTime)
          throws Exception {
    requestHandlerLOG.debug("=-----=-----= Step 1 - Adding ACK Records =-----=-----=");
    int deploymentNumber = serverlessNameNode.getDeploymentNumber();

    ZKClient zkClient = serverlessNameNode.getZooKeeperClient();
    assert(zkClient != null);
    List<String> groupMemberIds = zkClient.getGroupMembers(serverlessNameNode.getFunctionName());
    List<ActiveNode> activeNodes = serverlessNameNode.getActiveNameNodes().getActiveNodes();
    requestHandlerLOG.debug("Active NameNodes at start of consistency protocol: " + activeNodes.toString());

    WriteAcknowledgementDataAccess<WriteAcknowledgement> writeAcknowledgementDataAccess =
            (WriteAcknowledgementDataAccess<WriteAcknowledgement>) HdfsStorageFactory.getDataAccess(WriteAcknowledgementDataAccess.class);

    List<WriteAcknowledgement> writeAcknowledgements = new ArrayList<WriteAcknowledgement>();

    // Iterate over all the current group members. For each group member, we create a WriteAcknowledgement object,
    // which we'll persist to intermediate storage. We skip ourselves, as we do not need to ACK our own write. We also
    // create an entry for each follower NN in the `writeAckMap` to keep track of whether they've ACK'd their entry.
    for (String memberIdAsString : groupMemberIds) {
      long memberId = Long.parseLong(memberIdAsString);

      // We do not need to add an entry for ourselves.
      if (memberId == serverlessNameNode.getId())
        continue;

      waitingForAcks.add(memberId);
      writeAcknowledgements.add(new WriteAcknowledgement(memberId, deploymentNumber,
              operationId, false, txStartTime, serverlessNameNode.getId()));
    }

    if (writeAcknowledgements.size() > 0) {
      requestHandlerLOG.debug("Preparing to add " + writeAcknowledgements.size()
              + " write acknowledgement(s) to intermediate storage.");
      writeAcknowledgementDataAccess.addWriteAcknowledgements(writeAcknowledgements, deploymentNumber);
    } else {
      requestHandlerLOG.debug("We're the only Active NN rn. No need to create any ACK entries.");
    }

    // Instantiate the CountDownLatch variable. The value is set to the number of ACKs that we need
    // before we can proceed with the transaction. Receiving an ACK and a follower NN leaving the group
    // will trigger a decrement.
    countDownLatch = new CountDownLatch(writeAcknowledgements.size());

    // This will be zero if we are the only active NameNode.
    return writeAcknowledgements;
  }

  /**
   * Perform Step (1) of the consistency protocol:
   *    The leader sets the INV flag of the target INode to 1 (i.e., true), thereby triggering a round of
   *    INVs from intermediate storage (NDB).
   *
   * @param invalidatedINodes The INodes involved in this write operation. We must invalidate these INodes.
   * @param nn The local serverless name node.
   * @param txStartTime The time at which the transaction began.
   */
  private void issueInitialInvalidations(Collection<INode> invalidatedINodes, ServerlessNameNode nn, long txStartTime)
          throws StorageException {
    requestHandlerLOG.debug("=-----=-----= Step 3 - Issuing Initial Invalidations =-----=-----=");

    InvalidationDataAccess<Invalidation> dataAccess =
            (InvalidationDataAccess<Invalidation>)HdfsStorageFactory.getDataAccess(InvalidationDataAccess.class);
    // long[] ids = invalidatedINodes.stream().mapToLong(INode::getId).toArray();

    List<Invalidation> invalidations = new ArrayList<>();
    for (INode invalidatedINode : invalidatedINodes) {
      // int inodeId, int parentId, long leaderNameNodeId, long txStartTime, long operationId
      invalidations.add(new Invalidation(invalidatedINode.getId(), invalidatedINode.getParentId(),
              nn.getId(), txStartTime, operationId));
    }

    dataAccess.addInvalidations(invalidations, nn.getDeploymentNumber());
  }

  public void setUp() throws IOException {

  }

  @Override
  protected final boolean shouldAbort(Exception e) {
    if (e instanceof RecoveryInProgressException.NonAbortingRecoveryInProgressException) {
      return false;
    }
    return true;
  }
}
