/*
 * Copyright 2008-2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.server;

import static voldemort.utils.Utils.croak;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import voldemort.VoldemortException;
import voldemort.client.ClientConfig;
import voldemort.client.protocol.RequestFormatType;
import voldemort.client.protocol.admin.AdminClient;
import voldemort.client.protocol.admin.ProtoBuffAdminClientRequestFormat;
import voldemort.client.rebalance.RebalanceClient;
import voldemort.client.rebalance.RebalanceClientConfig;
import voldemort.client.rebalance.RebalanceStealInfo;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.server.gossip.GossipService;
import voldemort.server.http.HttpService;
import voldemort.server.jmx.JmxService;
import voldemort.server.niosocket.NioSocketService;
import voldemort.server.protocol.RequestHandlerFactory;
import voldemort.server.protocol.SocketRequestHandlerFactory;
import voldemort.server.protocol.admin.AsyncOperationRunner;
import voldemort.server.scheduler.SchedulerService;
import voldemort.server.socket.SocketService;
import voldemort.server.storage.StorageService;
import voldemort.store.configuration.ConfigurationStorageEngine;
import voldemort.store.metadata.MetadataStore;
import voldemort.store.metadata.MetadataStore.VoldemortState;
import voldemort.utils.RebalanceUtils;
import voldemort.utils.SystemTime;
import voldemort.utils.Utils;
import voldemort.versioning.Versioned;
import voldemort.xml.ClusterMapper;

import com.google.common.collect.ImmutableList;

/**
 * This is the main server, it bootstraps all the services.
 * 
 * It can be embedded or run directly via it's main method.
 * 
 * @author jay
 * 
 */
public class VoldemortServer extends AbstractService {

    private static final Logger logger = Logger.getLogger(VoldemortServer.class.getName());
    public static final long DEFAULT_PUSHER_POLL_MS = 60 * 1000;

    private final static int ASYNC_REQUEST_THREADS = 8;
    private final static int ASYNC_REQUEST_CACHE_SIZE = 64;

    private final Node identityNode;
    private final List<VoldemortService> services;
    private final StoreRepository storeRepository;
    private final VoldemortConfig voldemortConfig;
    private final MetadataStore metadata;
    private final AsyncOperationRunner asyncRunner;

    public VoldemortServer(VoldemortConfig config) {
        super(ServiceType.VOLDEMORT);
        this.voldemortConfig = config;
        this.storeRepository = new StoreRepository();
        this.metadata = MetadataStore.readFromDirectory(new File(this.voldemortConfig.getMetadataDirectory()),
                                                        voldemortConfig.getNodeId());
        this.identityNode = metadata.getCluster().getNodeById(voldemortConfig.getNodeId());
        this.asyncRunner = new AsyncOperationRunner(ASYNC_REQUEST_THREADS, ASYNC_REQUEST_CACHE_SIZE);
        this.services = createServices();
    }

    public VoldemortServer(VoldemortConfig config, Cluster cluster) {
        super(ServiceType.VOLDEMORT);
        this.voldemortConfig = config;
        this.identityNode = cluster.getNodeById(voldemortConfig.getNodeId());
        this.storeRepository = new StoreRepository();
        // update cluster details in metaDataStore
        ConfigurationStorageEngine metadataInnerEngine = new ConfigurationStorageEngine("metadata-config-store",
                                                                                        voldemortConfig.getMetadataDirectory());
        metadataInnerEngine.put(MetadataStore.CLUSTER_KEY,
                                new Versioned<String>(new ClusterMapper().writeCluster(cluster)));
        this.metadata = new MetadataStore(metadataInnerEngine, voldemortConfig.getNodeId());
        this.asyncRunner = new AsyncOperationRunner(ASYNC_REQUEST_THREADS, ASYNC_REQUEST_CACHE_SIZE);
        this.services = createServices();
    }

    public AsyncOperationRunner getAsyncRunner() {
        return asyncRunner;
    }

    private List<VoldemortService> createServices() {

        /* Services are given in the order they must be started */
        List<VoldemortService> services = new ArrayList<VoldemortService>();
        SchedulerService scheduler = new SchedulerService(voldemortConfig.getSchedulerThreads(),
                                                          SystemTime.INSTANCE);
        services.add(new StorageService(storeRepository, metadata, scheduler, voldemortConfig));
        services.add(scheduler);
        services.add(this.asyncRunner);

        if(voldemortConfig.isHttpServerEnabled())
            services.add(new HttpService(this,
                                         storeRepository,
                                         RequestFormatType.VOLDEMORT_V1,
                                         voldemortConfig.getMaxThreads(),
                                         identityNode.getHttpPort()));
        if(voldemortConfig.isSocketServerEnabled()) {
            RequestHandlerFactory socketRequestHandlerFactory = new SocketRequestHandlerFactory(this.storeRepository,
                                                                                                this.metadata,
                                                                                                this.voldemortConfig,
                                                                                                this.asyncRunner);

            if(voldemortConfig.getUseNioConnector()) {
                logger.info("Using NIO Connector.");
                services.add(new NioSocketService(socketRequestHandlerFactory,
                                                  identityNode.getSocketPort(),
                                                  voldemortConfig.getSocketBufferSize(),
                                                  voldemortConfig.getNioConnectorSelectors(),
                                                  "nio-socket-server",
                                                  voldemortConfig.isJmxEnabled()));
            } else {
                logger.info("Using BIO Connector.");
                services.add(new SocketService(socketRequestHandlerFactory,
                                               identityNode.getSocketPort(),
                                               voldemortConfig.getCoreThreads(),
                                               voldemortConfig.getMaxThreads(),
                                               voldemortConfig.getSocketBufferSize(),
                                               "socket-server",
                                               voldemortConfig.isJmxEnabled()));
            }
        }

        if(voldemortConfig.isAdminServerEnabled()) {
            SocketRequestHandlerFactory adminRequestHandlerFactory = new SocketRequestHandlerFactory(this.storeRepository,
                                                                                                     this.metadata,
                                                                                                     this.voldemortConfig,
                                                                                                     this.asyncRunner);
            services.add(new SocketService(adminRequestHandlerFactory,
                                           identityNode.getAdminPort(),
                                           voldemortConfig.getAdminCoreThreads(),
                                           voldemortConfig.getAdminMaxThreads(),
                                           voldemortConfig.getAdminSocketBufferSize(),
                                           "admin-server",
                                           voldemortConfig.isJmxEnabled()));
        }

        if(voldemortConfig.isGossipEnabled()) {
            ClientConfig clientConfig = new ClientConfig().setMaxConnectionsPerNode(1)
                                                          .setMaxThreads(1)
                                                          .setConnectionTimeout(voldemortConfig.getAdminConnectionTimeout(),
                                                                                TimeUnit.MILLISECONDS)
                                                          .setSocketTimeout(voldemortConfig.getSocketTimeoutMs(),
                                                                            TimeUnit.MILLISECONDS)
                                                          .setSocketBufferSize(voldemortConfig.getAdminSocketBufferSize());
            AdminClient adminClient = new ProtoBuffAdminClientRequestFormat(this.metadata.getCluster(),
                                                                            clientConfig);
            services.add(new GossipService(this.metadata, adminClient, scheduler, voldemortConfig));
        }

        if(voldemortConfig.isJmxEnabled())
            services.add(new JmxService(this, this.metadata.getCluster(), storeRepository, services));

        return ImmutableList.copyOf(services);
    }

    @Override
    protected void startInner() throws VoldemortException {
        logger.info("Starting " + services.size() + " services.");
        long start = System.currentTimeMillis();
        for(VoldemortService service: services)
            service.start();
        long end = System.currentTimeMillis();
        logger.info("Startup completed in " + (end - start) + " ms.");

        // check serverState
        if(!metadata.getServerState().equals(VoldemortState.NORMAL_SERVER)) {
            logger.warn("Server started in " + metadata.getServerState() + " state.");
            RebalanceStealInfo stealInfo = metadata.getRebalancingStealInfo();

            boolean success = false;
            while(!success && stealInfo.getAttempt() < voldemortConfig.getMaxRebalancingAttempt()) {
                success = attemptRebalance(stealInfo);
            }

            // if failed more than maxAttempts
            if(!success) {
                logger.info("Force starting into NORMAL mode !!");
            }

            // clean all rebalancing state
            metadata.cleanAllRebalancingState();
        }
    }

    private boolean attemptRebalance(RebalanceStealInfo stealInfo) {
        stealInfo.setAttempt(stealInfo.getAttempt() + 1);
        logger.info("Restarting rebalance from node:" + stealInfo.getDonorId()
                    + " for partitionList:" + stealInfo.getPartitionList() + " as "
                    + stealInfo.getAttempt() + " attempt.");

        // restart rebalancing
        RebalanceClient rebalanceClient = new RebalanceClient(metadata.getCluster(),
                                                              new RebalanceClientConfig());
        try {
            int asyncTaskId = RebalanceUtils.rebalanceLocalNode(metadata,
                                                                stealInfo,
                                                                asyncRunner,
                                                                rebalanceClient.getAdminClient());
            rebalanceClient.getAdminClient()
                           .waitForCompletion(getIdentityNode().getId(),
                                              asyncTaskId,
                                              voldemortConfig.getRebalancingTimeout(),
                                              TimeUnit.SECONDS);
            return true;
        } catch(Exception e) {
            logger.error("Failed to rebalance from node:" + stealInfo.getDonorId()
                         + " for partitionList:" + stealInfo.getPartitionList() + " in "
                         + stealInfo.getAttempt() + " attempts.", e);
            return false;
        } finally {
            rebalanceClient.stop();
        }
    }

    /**
     * Attempt to shutdown the server. As much shutdown as possible will be
     * completed, even if intermediate errors are encountered.
     * 
     * @throws VoldemortException
     */
    @Override
    protected void stopInner() throws VoldemortException {
        List<VoldemortException> exceptions = new ArrayList<VoldemortException>();

        logger.info("Stopping services:" + getIdentityNode().getId());
        /* Stop in reverse order */
        for(VoldemortService service: Utils.reversed(services)) {
            try {
                service.stop();
            } catch(VoldemortException e) {
                exceptions.add(e);
                logger.error(e);
            }
        }
        logger.info("All services stopped for Node:" + getIdentityNode().getId());

        if(exceptions.size() > 0)
            throw exceptions.get(0);
    }

    public static void main(String[] args) throws Exception {
        VoldemortConfig config = null;
        try {
            if(args.length == 0)
                config = VoldemortConfig.loadFromEnvironmentVariable();
            else if(args.length == 1)
                config = VoldemortConfig.loadFromVoldemortHome(args[0]);
            else
                croak("USAGE: java " + VoldemortServer.class.getName() + " [voldemort_home_dir]");
        } catch(Exception e) {
            logger.error(e);
            Utils.croak("Error while loading configuration: " + e.getMessage());
        }

        final VoldemortServer server = new VoldemortServer(config);
        if(!server.isStarted())
            server.start();

        // add a shutdown hook to stop the server
        Runtime.getRuntime().addShutdownHook(new Thread() {

            @Override
            public void run() {
                if(server.isStarted())
                    server.stop();
            }
        });
    }

    public Node getIdentityNode() {
        return this.identityNode;
    }

    public List<VoldemortService> getServices() {
        return services;
    }

    public VoldemortService getService(ServiceType type) {
        for(VoldemortService service: services)
            if(service.getType().equals(type))
                return service;
        throw new IllegalStateException(type.getDisplayName() + " has not been initialized.");
    }

    public VoldemortConfig getVoldemortConfig() {
        return this.voldemortConfig;
    }

    public StoreRepository getStoreRepository() {
        return this.storeRepository;
    }

    public MetadataStore getMetadataStore() {
        return metadata;
    }
}
