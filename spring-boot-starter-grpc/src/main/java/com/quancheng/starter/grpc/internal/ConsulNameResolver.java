package com.quancheng.starter.grpc.internal;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.google.common.base.Preconditions;
import com.google.common.net.InetAddresses;
import com.quancheng.starter.grpc.GrpcConstants;
import com.quancheng.starter.grpc.registry.NotifyListener;
import com.quancheng.starter.grpc.registry.Registry;
import com.quancheng.starter.grpc.registry.RegistryFactory;
import com.quancheng.starter.grpc.registry.URL;
import com.quancheng.starter.grpc.registry.URLParamType;
import com.quancheng.starter.grpc.registry.util.NetUtils;

import io.grpc.Attributes;
import io.grpc.NameResolver;
import io.grpc.ResolvedServerInfo;
import io.grpc.Status;

public class ConsulNameResolver extends NameResolver {

    private final String                       authority;
    private final Registry                     registry;
    private final URL                          refUrl;
    @GuardedBy("this")
    private boolean                            shutdown;
    @GuardedBy("this")
    private Listener                           listener;

    public static final Attributes.Key<String> PARAMS_DEFAULT_SERVICESNAME = Attributes.Key.of("serviceName");

    public static final Attributes.Key<String> PARAMS_DEFAULT_GROUP        = Attributes.Key.of("group");

    public static final Attributes.Key<String> PARAMS_DEFAULT_VERSION      = Attributes.Key.of("version");

    private NotifyListener                     notifyListener              = new NotifyListener() {

                                                                               @Override
                                                                               public void notify(URL registryUrl,
                                                                                                  List<URL> urls) {
                                                                                   if (urls != null
                                                                                       && !urls.isEmpty()) {
                                                                                       List<ResolvedServerInfo> servers = new ArrayList<ResolvedServerInfo>(urls.size());
                                                                                       for (int i = 0; i < urls.size(); i++) {
                                                                                           URL url = urls.get(i);
                                                                                           String ip = url.getHost();
                                                                                           int port = url.getPort();
                                                                                           servers.add(new ResolvedServerInfo(new InetSocketAddress(InetAddresses.forString(ip),
                                                                                                                                                    port),
                                                                                                                              Attributes.EMPTY));
                                                                                       }
                                                                                       ConsulNameResolver.this.listener.onUpdate(Collections.singletonList(servers),
                                                                                                                                 Attributes.EMPTY);
                                                                                   } else {
                                                                                       ConsulNameResolver.this.listener.onError(Status.NOT_FOUND.withDescription("There is no service registy in consul by"
                                                                                                                                                                 + refUrl.toFullStr()));
                                                                                   }
                                                                               }
                                                                           };

    public ConsulNameResolver(@Nullable String nsAuthority, String name, Attributes params){
        URI nameUri = URI.create("//" + name);
        authority = Preconditions.checkNotNull(nameUri.getAuthority(), "nameUri (%s) doesn't have an authority",
                                               nameUri);
        final String host = Preconditions.checkNotNull(nameUri.getHost(), "host");
        final int port;
        if (nameUri.getPort() == -1) {
            Integer defaultPort = params.get(NameResolver.Factory.PARAMS_DEFAULT_PORT);
            if (defaultPort != null) {
                port = defaultPort;
            } else {
                throw new IllegalArgumentException("name '" + name
                                                   + "' doesn't contain a port, and default port is not set in params");
            }
        } else {
            port = nameUri.getPort();
        }
        URL registerUrl = new URL("consul", host, port, "");
        registry = RegistryFactory.getRegistry(registerUrl);
        Map<String, String> urlParam = new HashMap<String, String>();
        urlParam.put(URLParamType.protocol.getName(), GrpcConstants.DEFAULT_PROTOCOL);
        urlParam.put(URLParamType.group.getName(), params.get(ConsulNameResolver.PARAMS_DEFAULT_GROUP));
        urlParam.put(URLParamType.version.getName(), params.get(ConsulNameResolver.PARAMS_DEFAULT_VERSION));
        refUrl = new URL(GrpcConstants.DEFAULT_PROTOCOL, NetUtils.getLocalAddress().getHostAddress(),
                         GrpcConstants.DEFAULT_INT_VALUE, params.get(PARAMS_DEFAULT_SERVICESNAME), urlParam);
    }

    @Override
    public final String getServiceAuthority() {
        return authority;
    }

    @Override
    public final synchronized void refresh() {
        Preconditions.checkState(listener != null, "not started");
        resolve();
    }

    @Override
    public final synchronized void start(Listener listener) {
        Preconditions.checkState(this.listener == null, "already started");
        this.listener = listener;
        resolve();
    }

    private void resolve() {
        if (shutdown) {
            return;
        }
        registry.subscribe(refUrl, notifyListener);
    }

    @Override
    public final synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        registry.unsubscribe(refUrl, notifyListener);
    }

}
