/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.andes.thrift;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.wso2.andes.configuration.AndesConfigurationManager;
import org.wso2.andes.configuration.enums.AndesConfiguration;
import org.wso2.andes.kernel.AndesContext;
import org.wso2.andes.kernel.slot.ConnectionException;
import org.wso2.andes.kernel.slot.Slot;
import org.wso2.andes.server.cluster.ClusterAgent;
import org.wso2.andes.thrift.exception.ThriftClientException;
import org.wso2.andes.thrift.slot.gen.SlotInfo;
import org.wso2.andes.thrift.slot.gen.SlotManagementService;

import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A wrapper client for the native thrift client. All the public methods in this class are
 * synchronized in order to avoid out of sequence response exception from thrift server. Only one
 * method should be triggered at a time in order to get the responses from the server in order.
 */

public class MBThriftClient {

    /**
     * A state variable to indicate whether the reconnecting  to the thrift server is started or
     * not
     */
    private static boolean reconnectingStarted = false;

    private static TTransport transport;

    private static SlotManagementService.Client client = null;

    private static final Log log = LogFactory.getLog(MBThriftClient.class);

    private static final Queue<ThriftConnectionListener> connectionListenerQueue = new ConcurrentLinkedQueue<>();

    private static AtomicBoolean isConnected = new AtomicBoolean(false);

    /**
     * getSlot method. Returns Slot Object, when the
     * queue name is given
     *
     * @param queueName name of the queue
     * @param nodeId    of this node
     * @return slot object
     * @throws ConnectionException
     */
    public static synchronized Slot getSlot(String queueName, String nodeId) throws ConnectionException {
        SlotInfo slotInfo;
        try {
            client = getServiceClient();
            slotInfo = client.getSlotInfo(queueName, nodeId);
            return convertSlotInforToSlot(slotInfo);
        } catch (TException e) {
            try {
                //retry once
                reConnectToServer();
                slotInfo = client.getSlotInfo(queueName, nodeId);
                return convertSlotInforToSlot(slotInfo);
            } catch (TException e1) {
                handleCoordinatorChanges();
                throw new ConnectionException("Coordinator has changed", e);
            }
        } catch (ThriftClientException e) {
            handleCoordinatorChanges();
            throw new ConnectionException("Error occurred in thrift client " + e.getMessage(), e);
        }
    }

    /**
     * Add Thrift connection listener
     *
     * @param connectionListener {@link ThriftConnectionListener}
     *
     */
    public static void addConnectionListener(ThriftConnectionListener connectionListener) {
        connectionListenerQueue.add(connectionListener);
    }

    /**
     * Convert SlotInfo object to Slot object
     *
     * @param slotInfo object generated by thrift
     * @return slot object
     */
    private static Slot convertSlotInforToSlot(SlotInfo slotInfo) {
        Slot slot = new Slot();
        slot.setStartMessageId(slotInfo.getStartMessageId());
        slot.setEndMessageId(slotInfo.getEndMessageId());
        slot.setStorageQueueName(slotInfo.getQueueName());
        return slot;
    }

    /**
     * updateMessageId method. This method will pass the locally chosen slot range to the SlotManagerClusterMode. Slot manager
     * maintains a list of slot ranges in a map along with the queue. This messageId will
     * be stored in that map.
     *
     * @param queueName name of the queue
     * @param nodeId unique hazelcast identifier of node.
     * @param startMessageId start message Id of the locally chosen slot.
     * @param endMessageId end message Id of the locally chosen slot.
     * @param localSafeZone Minimum message ID of the node that is deemed safe.
     * @throws TException in case of an connection error
     */
    public static synchronized void updateMessageId(String queueName, String nodeId,
                                                    long startMessageId, long endMessageId, long localSafeZone) throws ConnectionException {
        try {
            client = getServiceClient();
            client.updateMessageId(queueName, nodeId, startMessageId, endMessageId, localSafeZone);
        } catch (TException e) {
            try {
                //retry once
                reConnectToServer();
                client.updateMessageId(queueName, nodeId, startMessageId, endMessageId, localSafeZone);
            } catch (TException e1) {
                handleCoordinatorChanges();
                throw new ConnectionException("Coordinator has changed", e);
            }

        } catch (ThriftClientException e) {
            log.error("Error occurred while receiving coordinator details from map", e);
            handleCoordinatorChanges();
        }
    }

    /**
     * Delete the slot from SlotAssignmentMap when all the messages in the slot has been sent and
     * all the acks are received.
     *
     * @param queueName  name of the queue where slot belongs to
     * @param slot      to be deleted
     * @throws TException
     */
    public static synchronized boolean deleteSlot(String queueName, Slot slot,
                                               String nodeId) throws ConnectionException {
        SlotInfo slotInfo = new SlotInfo(slot.getStartMessageId(), slot.getEndMessageId(),
                slot.getStorageQueueName(),nodeId,slot.isAnOverlappingSlot());
        boolean deleteSuccess = false;
        try {
            client = getServiceClient();
            deleteSuccess = client.deleteSlot(queueName, slotInfo, nodeId);
        } catch (TException e) {
            try {
                //retry to connect once
                reConnectToServer();
                deleteSuccess = client.deleteSlot(queueName, slotInfo, nodeId);
            } catch (TException e1) {
                handleCoordinatorChanges();
                throw new ConnectionException("Coordinator has changed", e);
            }
        } catch (ThriftClientException e) {
            log.error("Error occurred while receiving coordinator details from map", e);
            handleCoordinatorChanges();
        }

        return deleteSuccess;
    }

    /**
     * Re-assign the slot when the last subscriber leaves the node
     *
     * @param nodeId    of this node
     * @param queueName name of the queue
     * @throws TException
     */
    public static synchronized void reAssignSlotWhenNoSubscribers(String nodeId,
                                                                  String queueName) throws ConnectionException {
        try {
            client = getServiceClient();
            client.reAssignSlotWhenNoSubscribers(nodeId, queueName);
        } catch (TException e) {
            try {
                //retry to do the operation once
                reConnectToServer();
                client.reAssignSlotWhenNoSubscribers(nodeId, queueName);
            } catch (TException e1) {
                handleCoordinatorChanges();
                throw new ConnectionException("Coordinator has changed", e);
            }
        } catch (ThriftClientException e) {
            log.error("Error occurred while receiving coordinator details from map", e);
            handleCoordinatorChanges();
        }
    }

    /**
     * Delete all in-memory slot associations with a given queue. This is required to handle a queue purge event.
     *
     * @param queueName name of destination queue
     * @throws ConnectionException
     */
    public static synchronized void clearAllActiveSlotRelationsToQueue(String queueName) throws ConnectionException {

        try {
            client = getServiceClient();
            client.clearAllActiveSlotRelationsToQueue(queueName);
        } catch (TException e) {
            try {
                //retry to do the operation once
                reConnectToServer();
                client.clearAllActiveSlotRelationsToQueue(queueName);
            } catch (TException e1) {
                handleCoordinatorChanges();
                throw new ConnectionException("Coordinator has changed", e);
            }
        } catch (ThriftClientException e) {
            log.error("Could not initialize the Thrift client." + e.getMessage(), e);
            handleCoordinatorChanges();
        }
    }

    /**
     * Returns an instance of Slot Management service client which is used to communicate to the
     * thrift server. If it does not succeed in connecting to the server, it throws a  TTransportException
     *
     * @return a SlotManagementService client
     */
    private static SlotManagementService.Client getServiceClient() throws TTransportException, ThriftClientException {

        if (client == null) {
            ClusterAgent clusterAgent =  AndesContext.getInstance().getClusterAgent();
            InetSocketAddress thriftAddressOfCoordinator = clusterAgent.getThriftAddressOfCoordinator();

            if (null == thriftAddressOfCoordinator) {
                throw new ThriftClientException("Thrift coordinator details are not updated in the map yet");
            }

            int soTimeout = AndesConfigurationManager.readValue(AndesConfiguration.COORDINATION_THRIFT_SO_TIMEOUT);

            transport = new TSocket(thriftAddressOfCoordinator.getHostName(), thriftAddressOfCoordinator.getPort(),
                    soTimeout);
            try {
                transport.open();
                TProtocol protocol = new TBinaryProtocol(transport);
                return new SlotManagementService.Client(protocol);
            } catch (TTransportException e) {
                log.error("Could not initialize the Thrift client", e);
                throw new TTransportException("Could not initialize the Thrift client", e);
            }
        }

        return client;
    }

    /**
     * Start the thrift server reconnecting thread when the coordinator of the cluster is changed.
     */
    private static void handleCoordinatorChanges() {

        notifyDisconnection();
        resetServiceClient();
        if (!isReconnectingStarted()) {
            setReconnectingFlag(true);
            startServerReconnectingThread();
        }
    }

    /**
     * Notify Thrift connection disconnect to the {@link ThriftConnectionListener}s
     */
    private static void notifyDisconnection() {
        if (isConnected.compareAndSet(true, false)) {
            for (ThriftConnectionListener listener : connectionListenerQueue) {
                listener.onThriftClientDisconnect();
            }
        }
    }

    /**
     * Set mbThriftClient to null
     */
    private static void resetServiceClient() {
        client = null;
        transport.close();
    }

    /**
     * Try to reconnect to server by taking latest values in the hazelcalst thrift server details
     * map
     *
     * @throws TTransportException when connecting to thrift server is unsuccessful
     */
    private static void reConnectToServer() throws TTransportException {
        Long reconnectTimeout = (Long) AndesConfigurationManager.readValue
                (AndesConfiguration.COORDINATOR_THRIFT_RECONNECT_TIMEOUT) * 1000;
        try {
            //Reconnect timeout set because Hazelcast coordinator may still not elected in failover scenario
            Thread.sleep(reconnectTimeout);

            ClusterAgent clusterAgent =  AndesContext.getInstance().getClusterAgent();
            InetSocketAddress thriftAddressOfCoordinator = clusterAgent.getThriftAddressOfCoordinator();

            if (null == thriftAddressOfCoordinator) {
                throw new TTransportException("Thrift coordinator details are not updated in the map yet");
            }

            int soTimeout = AndesConfigurationManager.readValue(AndesConfiguration.COORDINATION_THRIFT_SO_TIMEOUT);

            transport = new TSocket(thriftAddressOfCoordinator.getHostName(), thriftAddressOfCoordinator.getPort(),
                    soTimeout);
            log.info("Reconnecting to Slot Coordinator " + thriftAddressOfCoordinator.toString());

            transport.open();
            TProtocol protocol = new TBinaryProtocol(transport);
            client = new SlotManagementService.Client(protocol);
            notifyConnection();
        } catch (TTransportException e) {
            log.error("Could not connect to the Thrift Server" , e);
            throw new TTransportException("Could not connect to the Thrift Server", e);
        } catch (InterruptedException ignore) {
        }
    }

    /**
     * Notify Thrift connection establish signal to the {@link ThriftConnectionListener}
     */
    private static void notifyConnection() {
        if (isConnected.compareAndSet(false, true)) {
            for (ThriftConnectionListener listener : connectionListenerQueue) {
                listener.onThriftClientConnect();
            }
        }
    }

    /**
     * This thread is responsible of reconnecting to the thrift server of the coordinator until it
     * gets succeeded
     */
    private static void startServerReconnectingThread() {
        new Thread() {
            public void run() {

                // This thread will try to connect to thrift server while reconnectingStarted flag is true
                // After successfully connecting to the server this flag will be set to true.
                // While loop is therefore intentional.
                while (reconnectingStarted) {

                    try {
                        reConnectToServer();
                        // If re connect to server is successful, following code segment will be executed
                        reconnectingStarted = false;
                    } catch (Throwable e) {
                        log.error("Error occurred while reconnecting to slot coordinator", e);

                        try {
                            TimeUnit.SECONDS.sleep(2);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }.start();
    }


    /**
     * A flag to specify whether the reconnecting to thrift server is happening or not
     *
     * @return whether the reconnecting to thrift server is happening or not
     */
    public static boolean isReconnectingStarted() {
        return reconnectingStarted;
    }

    /**
     * Set reconnecting flag
     *
     * @param reconnectingFlag  flag to see whether the reconnecting to the thrift server is
     *                          started
     */
    public static void setReconnectingFlag(boolean reconnectingFlag) {
        reconnectingStarted = reconnectingFlag;
    }

    /**
     * Update the safeZone message ID of this node
     * @param safeZoneMessageID safe zone message ID
     * @param nodeID node ID of this node
     * @return global safeZone
     * @throws ConnectionException when MB thrift server is down
     */
    public static synchronized long updateSlotDeletionSafeZone(long safeZoneMessageID, String nodeID) throws ConnectionException {
        long globalSafeZone = 0;
        try {
            client = getServiceClient();
            globalSafeZone = client.updateCurrentMessageIdForSafeZone(safeZoneMessageID, nodeID);
        } catch (TException e) {
            try {
                //retry once
                reConnectToServer();
                globalSafeZone = client.updateCurrentMessageIdForSafeZone(safeZoneMessageID, nodeID);
                return globalSafeZone;
            } catch (TException e1) {
                handleCoordinatorChanges();
                throw new ConnectionException("Coordinator has changed", e);
            }
        } catch (ThriftClientException e) {
            log.error("Error occurred while receiving coordinator details from map", e);
            handleCoordinatorChanges();
        }

        return globalSafeZone;
    }
}
