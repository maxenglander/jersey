/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.jersey.server.internal.routing;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.internal.inject.Injections;
import org.glassfish.jersey.process.Inflector;
import org.glassfish.jersey.process.internal.ProcessingContext;
import org.glassfish.jersey.process.internal.RequestInvoker;
import org.glassfish.jersey.process.internal.RequestScope;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.InvokerBuilder;
import org.glassfish.jersey.server.RequestContextBuilder;
import org.glassfish.jersey.server.ServerBinder;
import org.glassfish.jersey.server.internal.routing.RouterBinder.RootRouteBuilder;

import org.glassfish.hk2.api.ServiceLocator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.assertEquals;

/**
 * Context injected async app test.
 *
 * @author Paul Sandoz
 */
@RunWith(Parameterized.class)
public class ContextInjectedAsyncAppTest {

    private static final URI BASE_URI = URI.create("http://localhost:8080/base/");

    @Parameterized.Parameters
    public static List<String[]> testUriSuffixes() {
        return Arrays.asList(new String[][]{
                {"a/b/c", "B-c-b-a"},
                {"a/b/d/", "B-d-b-a"}
        });
    }

    @Context
    private RootRouteBuilder<Pattern> routeBuilder;
    private RequestScope requestScope;
    private RequestInvoker<ContainerRequest, ContainerResponse> invoker;
    private final String uriSuffix;
    private final String expectedResponse;

    public ContextInjectedAsyncAppTest(String uriSuffix, String expectedResponse) {
        this.uriSuffix = uriSuffix;
        this.expectedResponse = expectedResponse;
    }

    private static class AsyncInflector implements Inflector<ContainerRequest, ContainerResponse> {

        @Context
        private ProcessingContext processingContext;
        @Context
        ServiceLocator locator;
        private final ServiceLocator i;

        public AsyncInflector(ServiceLocator i) {
            this.i = i;
        }

        @Override
        public ContainerResponse apply(final ContainerRequest req) {
            i.inject(this);
            // Suspend current request
            processingContext.suspend();

            Executors.newSingleThreadExecutor().submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace(System.err);
                    }

                    // Returning will enter the suspended request
                    processingContext.resume(Response.ok().entity("B").build());
                }
            });

            return null;
        }
    }

    @Before
    public void setupApplication() {
        ServiceLocator locator = Injections.createLocator(new ServerBinder(), new AbstractBinder() {
            @Override
            protected void configure() {
                bindAsContract(LastPathSegmentTracingFilter.class);
            }
        });

        locator.inject(this);

        final InvokerBuilder invokerBuilder = locator.createAndInitialize(InvokerBuilder.class);
        this.invoker = invokerBuilder.build(routeBuilder.root(routeBuilder
                .route("a(/.*)?").to(LastPathSegmentTracingFilter.class)
                .to(routeBuilder.route("b(/.*)?").to(LastPathSegmentTracingFilter.class)
                        .to(routeBuilder.route("c(/)?").to(LastPathSegmentTracingFilter.class).to(Routers.asTreeAcceptor(new AsyncInflector(locator))))
                        .to(routeBuilder.route("d(/)?").to(LastPathSegmentTracingFilter.class).to(Routers.asTreeAcceptor(new AsyncInflector(locator)))))
                .build()));
        this.requestScope = locator.createAndInitialize(RequestScope.class);
    }

    @Test
    public void testAsyncApp() throws Exception {
        final ContainerRequest req =
                RequestContextBuilder.from(BASE_URI, URI.create(BASE_URI.getPath() + uriSuffix), "GET").build();

        Future<ContainerResponse> res =
                requestScope.runInScope(new Callable<Future<ContainerResponse>>() {

                    @Override
                    public Future<ContainerResponse> call() throws Exception {
                        return invoker.apply(req);
                    }
                });
        assertEquals(expectedResponse, res.get().getEntity());

    }
}
