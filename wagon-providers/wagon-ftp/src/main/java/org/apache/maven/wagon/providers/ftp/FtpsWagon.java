package org.apache.maven.wagon.providers.ftp;

import java.io.IOException;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FtpsWagon
 *
 *
 * @plexus.component role="org.apache.maven.wagon.Wagon"
 * role-hint="ftps"
 * instantiation-strategy="per-lookup"
 */
public class FtpsWagon
    extends FtpWagon
{
    private static final Logger LOG = LoggerFactory.getLogger( FtpsWagon.class );

    /**
     * @plexus.configuration default-value="TLS"
     */
    private String securityProtocol = "TLS";
    
    /**
     * @plexus.configuration default-value="0"
     */
    private int parmPBSZ = 0;
    
    /**
     * @plexus.configuration default-value="P"
     */
    private String dataConnMode = "P";
    
    /**
     * @plexus.configuration default-value="false"
     */
    private boolean implicit = false;

    /**
     * @plexus.configuration default-value="true"
     */
    private boolean endpointChecking = true;

    @Override
    protected FTPClient createClient()
    {
        LOG.debug( "Creating secure FTP client. Protocol: [{}], implicit mode: [{}], endpoint checking: [{}].",
                securityProtocol, implicit, endpointChecking );
        FTPSClient client = new FTPSClient( securityProtocol, implicit );
        client.setEndpointCheckingEnabled( endpointChecking );
        try {
			client.execPROT("P");
		} catch (IOException e) {
			LOG.info("Unable to change to protected data connection, continue with clear data connections.");
		}
        return client;
    }
    

	@Override
	protected void openConnectionInternal() throws ConnectionException, AuthenticationException {
		super.openConnectionInternal();
		
		try {
			LOG.debug("Try to switch to secure data connection.");
			((FTPSClient) ftp).execPBSZ(parmPBSZ);
			((FTPSClient) ftp).execPROT(dataConnMode);
		} catch (IOException e) {
			LOG.info("Unable to change to protected data connection, continue with clear data connections.");
		}
		LOG.debug("Secure DataConnection established.");
	}
}
