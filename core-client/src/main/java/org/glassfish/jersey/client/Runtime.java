/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.jersey.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.ws.rs.client.ClientException;

import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.process.internal.ChainableStage;
import org.glassfish.jersey.process.internal.RequestScope;
import org.glassfish.jersey.process.internal.Stage;
import org.glassfish.jersey.process.internal.Stages;

import org.glassfish.hk2.api.ServiceLocator;

/**
 * Client request processing runtime.
 *
 * @author Marek Potociar (marek.potociar at oracle.com)
 */
class Runtime {
    private final Stage<ClientRequest> requestProcessingRoot;
    private final Stage<ClientResponse> responseProcessingRoot;

    private final Connector connector;

    private final RequestScope requestScope;
    private final ClientAsyncExecutorsFactory asyncExecutorsFactory;

    /**
     * Create new client request processing runtime.
     *
     * @param connector client transport connector.
     * @param locator HK2 service locator.
     */
    public Runtime(final Connector connector, final ServiceLocator locator) {

        final Stage.Builder<ClientRequest> requestingChainBuilder = Stages
                .chain(locator.createAndInitialize(RequestProcessingInitializationStage.class));
        final ChainableStage<ClientRequest> requestFilteringStage =
                ClientFilteringStages.createRequestFilteringStage(locator);
        this.requestProcessingRoot = requestFilteringStage != null ?
                requestingChainBuilder.build(requestFilteringStage) : requestingChainBuilder.build();

        final ChainableStage<ClientResponse> responseFilteringStage =
                ClientFilteringStages.createResponseFilteringStage(locator);
        this.responseProcessingRoot = responseFilteringStage != null ?
                responseFilteringStage : Stages.<ClientResponse>identity();

        this.connector = connector;

        this.requestScope = locator.getService(RequestScope.class);
        this.asyncExecutorsFactory = new ClientAsyncExecutorsFactory(locator);
    }

    /**
     * Submit a {@link ClientRequest client request} for asynchronous processing.
     * <p>
     * Both, the request processing as well as response callback invocation will be executed
     * in a context of an active {@link RequestScope.Instance request scope instance}.
     * </p>
     *
     * @param request client request to be sent.
     * @param callback asynchronous response callback.
     */
    public void submit(final ClientRequest request, final ResponseCallback callback) {
        submit(asyncExecutorsFactory.getRequestingExecutor(request), new Runnable() {

            @Override
            public void run() {
                Stage.Continuation<ClientRequest> continuation = process(request, requestProcessingRoot);

                final RequestScope.Instance currentScopeInstance = requestScope.referenceCurrent();
                connector.apply(continuation.result(), new AsyncConnectorCallback() {

                    @Override
                    public void response(final ClientResponse response) {
                        submit(asyncExecutorsFactory.getRespondingExecutor(request), currentScopeInstance, new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    callback.completed(process(response, responseProcessingRoot).result(), requestScope);
                                } finally {
                                    currentScopeInstance.release();
                                }
                            }
                        });
                    }

                    @Override
                    public void failure(Throwable failure) {
                        if (failure instanceof AbortException) {
                            response(((AbortException) failure).getAbortResponse());
                        }
                        try {
                            callback.failed(failure instanceof ClientException ?
                                    (ClientException) failure : new ClientException(failure));
                        } finally {
                            currentScopeInstance.release();
                        }
                    }
                });
            }
        });
    }

    private Future<?> submit(final ExecutorService executor, final Runnable task) {
        return executor.submit(new Runnable() {
            @Override
            public void run() {
                requestScope.runInScope(task);
            }
        });
    }

    private Future<?> submit(final ExecutorService executor, final RequestScope.Instance scopeInstance, final Runnable task) {
        return executor.submit(new Runnable() {
            @Override
            public void run() {
                requestScope.runInScope(scopeInstance, task);
            }
        });
    }

    /**
     * Invoke a request processing synchronously in the context of the caller's thread.
     * <p>
     * NOTE: the method does not explicitly start a new request scope context. Instead
     * it is assumed that the method is invoked from within a context of a proper, running
     * {@link RequestScope.Instance request scope instance}. A caller may use the
     * {@link #getRequestScope()} method to retrieve the request scope instance and use it to
     * initialize the proper request scope context prior the method invocation.
     * </p>
     *
     * @param request client request to be invoked.
     * @return client response.
     * @throws ClientException in case of an invocation failure.
     */
    public ClientResponse invoke(final ClientRequest request) throws ClientException {
        ClientResponse response;
        try {
            try {
                Stage.Continuation<ClientRequest> continuation = process(request, requestProcessingRoot);
                response = connector.apply(continuation.result());
            } catch (AbortException aborted) {
                response = aborted.getAbortResponse();
            }

            return process(response, responseProcessingRoot).result();
        } catch (ClientException ex) {
            throw ex;
        } catch (Throwable t) {
            throw new ClientException(t.getMessage(), t);
        }
    }

    /**
     * Get the request scope instance configured for the runtime.
     *
     * @return request scope instance.
     */
    public RequestScope getRequestScope() {
        return requestScope;
    }

    public void close() {
        connector.close();
    }

    private <DATA> Stage.Continuation<DATA> process(DATA data, Stage<DATA> processingRoot) {
        Stage.Continuation<DATA> continuation = Stage.Continuation.of(data, processingRoot);
        Stage<DATA> currentStage;
        while ((currentStage = continuation.next()) != null) {
            continuation = currentStage.apply(continuation.result());
        }
        return continuation;
    }
}
