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
package org.glassfish.jersey.tests.e2e.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.logging.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.MessageProcessingException;
import javax.ws.rs.Path;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientResponseContext;
import javax.ws.rs.client.ClientResponseFilter;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.filter.LoggingFilter;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;

import org.junit.Test;
import static org.junit.Assert.fail;

/**
 * @author Michal Gajdos (michal.gajdos at oracle.com)
 */
public class ResponseBufferEntityTest extends JerseyTest {

    private static final Logger LOGGER = Logger.getLogger(ResponseBufferEntityTest.class.getName());

    public static class CorruptedInputStream extends ByteArrayInputStream {

        private boolean corrupted = false;

        public CorruptedInputStream(byte[] buf) {
            super(buf);
        }

        @Override
        public void close() throws IOException {
            if (corrupted)
                throw new IOException("CorruptedInputStream test IOException");
            else
                super.close();
        }

        public void setCorrupted(boolean corrupted) {
            this.corrupted = corrupted;
        }
    }

    @Path("response")
    public static class Resource {

        public static final String ENTITY = "ENtiTy";

        @GET
        @Path("corrupted")
        public CorruptedInputStream corrupted() {
            return new CorruptedInputStream(ENTITY.getBytes());
        }
    }

    @Override
    protected Application configure() {
        enable(TestProperties.DUMP_ENTITY);
        enable(TestProperties.LOG_TRAFFIC);

        return new ResourceConfig(Resource.class).addSingletons(new LoggingFilter(LOGGER, true));
    }

    @Override
    protected void configureClient(final ClientConfig clientConfig) {
        clientConfig.register(new ClientResponseFilter() {

            @Override
            public void filter(final ClientRequestContext requestContext, final ClientResponseContext responseContext)
                    throws IOException {
                final CorruptedInputStream cis = new CorruptedInputStream(Resource.ENTITY.getBytes());
                cis.setCorrupted(true);
                responseContext.setEntityStream(cis);
            }

        });
    }

    @Test
    public void testBufferEntityTest() throws Exception {
        final Response response = target("response/corrupted").request().buildGet().invoke();

        try {
            response.bufferEntity();
        } catch (MessageProcessingException mpe) {
            // OK
            return;
        }

        fail("MessageProcessingException expected.");
    }

}
