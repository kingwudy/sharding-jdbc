package io.shardingjdbc.orchestration.reg.etcd;

import com.google.common.base.Optional;
import com.google.protobuf.ByteString;
import etcdserverpb.KVGrpc;
import etcdserverpb.KVGrpc.KVFutureStub;
import etcdserverpb.LeaseGrpc;
import etcdserverpb.LeaseGrpc.LeaseFutureStub;
import etcdserverpb.Rpc.LeaseGrantRequest;
import etcdserverpb.Rpc.PutRequest;
import etcdserverpb.Rpc.RangeRequest;
import etcdserverpb.Rpc.RangeResponse;
import etcdserverpb.Rpc.WatchCreateRequest;
import etcdserverpb.Rpc.WatchRequest;
import etcdserverpb.WatchGrpc;
import etcdserverpb.WatchGrpc.WatchStub;
import io.grpc.Channel;
import io.shardingjdbc.orchestration.reg.api.CoordinatorRegistryCenter;
import io.shardingjdbc.orchestration.reg.listener.EventListener;
import io.shardingjdbc.orchestration.reg.etcd.internal.channel.EtcdChannelFactory;
import io.shardingjdbc.orchestration.reg.etcd.internal.retry.EtcdRetryEngine;
import io.shardingjdbc.orchestration.reg.etcd.internal.watcher.EtcdWatchStreamObserver;
import io.shardingjdbc.orchestration.reg.exception.RegException;
import mvccpb.Kv.KeyValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Etcd based registry center.
 *
 * @author junxiong
 */
public final class EtcdRegistryCenter implements CoordinatorRegistryCenter {
    
    private final EtcdConfiguration etcdConfig;
    
    private final EtcdRetryEngine etcdRetryEngine;
    
    private final KVFutureStub kvStub;
    
    private final LeaseFutureStub leaseStub;
    
    private final WatchStub watchStub;
    
    public EtcdRegistryCenter(final EtcdConfiguration etcdConfig) {
        this.etcdConfig = etcdConfig;
        etcdRetryEngine = new EtcdRetryEngine(etcdConfig);
        Channel channel = EtcdChannelFactory.getInstance(Arrays.asList(etcdConfig.getServerLists().split(",")));
        kvStub = KVGrpc.newFutureStub(channel);
        leaseStub = LeaseGrpc.newFutureStub(channel);
        watchStub = WatchGrpc.newStub(channel);
    }
    
    @Override
    public void init() {
    }
    
    @Override
    public String get(final String key) {
        final RangeRequest request = RangeRequest.newBuilder().setKey(ByteString.copyFromUtf8(getFullPathWithNamespace(key))).build();
        return etcdRetryEngine.execute(new Callable<String>() {
            
            @Override
            public String call() throws Exception {
                RangeResponse response = kvStub.range(request).get(etcdConfig.getTimeoutMilliseconds(), TimeUnit.MILLISECONDS);
                return response.getKvsCount() > 0 ? response.getKvs(0).getValue().toStringUtf8() : null;
            }
        }).orNull();
    }
    
    @Override
    public boolean isExisted(final String key) {
        return null != get(key);
    }
    
    @Override
    public void persist(final String key, final String value) {
        final PutRequest request = PutRequest.newBuilder().setPrevKv(true).setKey(ByteString.copyFromUtf8(getFullPathWithNamespace(key))).setValue(ByteString.copyFromUtf8(value)).build();
        etcdRetryEngine.execute(new Callable<Void>() {
            
            @Override
            public Void call() throws Exception {
                kvStub.put(request).get(etcdConfig.getTimeoutMilliseconds(), TimeUnit.MILLISECONDS);
                return null;
            }
        });
    }
    
    @Override
    public void update(final String key, final String value) {
        persist(key, value);
    }
    
    @Override
    public String getDirectly(final String key) {
        return get(key);
    }
    
    @Override
    public void persistEphemeral(final String key, final String value) {
        String fullPath = getFullPathWithNamespace(key);
        final Optional<Long> leaseId = lease();
        if (!leaseId.isPresent()) {
            throw new RegException("Unable to set up heat beat for key %s", fullPath);
        }
        final PutRequest request = PutRequest.newBuilder().setPrevKv(true).setLease(leaseId.get()).setKey(ByteString.copyFromUtf8(fullPath)).setValue(ByteString.copyFromUtf8(value)).build();
        etcdRetryEngine.execute(new Callable<Void>() {
            
            @Override
            public Void call() throws Exception {
                kvStub.put(request).get(etcdConfig.getTimeoutMilliseconds(), TimeUnit.MILLISECONDS);
                return null;
            }
        });
    }
    
    private Optional<Long> lease() {
        final LeaseGrantRequest request = LeaseGrantRequest.newBuilder().setTTL(etcdConfig.getTimeToLiveMilliseconds()).build();
        return etcdRetryEngine.execute(new Callable<Long>() {
            
            @Override
            public Long call() throws Exception {
                return leaseStub.leaseGrant(request).get(etcdConfig.getTimeoutMilliseconds(), TimeUnit.MILLISECONDS).getID();
            }
        });
    }
    
    @Override
    public List<String> getChildrenKeys(final String key) {
        String fullPath = getFullPathWithNamespace(key);
        final RangeRequest request = RangeRequest.newBuilder().setKey(ByteString.copyFromUtf8(fullPath)).setRangeEnd(getRangeEnd(fullPath)).build();
        Optional<List<String>> result = etcdRetryEngine.execute(new Callable<List<String>>() {
            
            @Override
            public List<String> call() throws Exception {
                RangeResponse response = kvStub.range(request).get(etcdConfig.getTimeoutMilliseconds(), TimeUnit.MILLISECONDS);
                List<String> result = new ArrayList<>();
                for (KeyValue each : response.getKvsList()) {
                    result.add(each.getKey().toStringUtf8());
                }
                return result;
            }
        });
        return result.isPresent() ? result.get() : Collections.<String>emptyList();
    }
    
    @Override
    public void watch(final String key, final EventListener eventListener) {
        String fullPath = getFullPathWithNamespace(key);
        WatchCreateRequest createWatchRequest = WatchCreateRequest.newBuilder().setKey(ByteString.copyFromUtf8(fullPath)).setRangeEnd(getRangeEnd(fullPath)).build();
        final WatchRequest request = WatchRequest.newBuilder().setCreateRequest(createWatchRequest).build();
        etcdRetryEngine.execute(new Callable<Void>() {
            
            @Override
            public Void call() throws Exception {
                watchStub.watch(new EtcdWatchStreamObserver(eventListener)).onNext(request);
                return null;
            }
        });
    }
    
    @Override
    public void close() {
    }
    
    private String getFullPathWithNamespace(final String path) {
        return String.format("/%s/%s", etcdConfig.getNamespace(), path);
    }
    
    private ByteString getRangeEnd(final String key) {
        byte[] noPrefix = {0};
        byte[] endKey = key.getBytes().clone();
        for (int i = endKey.length - 1; i >= 0; i--) {
            if (endKey[i] < 0xff) {
                endKey[i] = (byte) (endKey[i] + 1);
                return ByteString.copyFrom(Arrays.copyOf(endKey, i + 1));
            }
        }
        return ByteString.copyFrom(noPrefix);
    }
}
