package org.apache.maven.wagon.providers.ftp;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
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

import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.ProtocolCommandListener;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.InputData;
import org.apache.maven.wagon.OutputData;
import org.apache.maven.wagon.PathUtils;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.StreamWagon;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.WagonConstants;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.repository.RepositoryPermissions;
import org.apache.maven.wagon.resource.Resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class FtpWagon
    extends StreamWagon
{
    private FTPClient ftp;

    public void openConnection()
        throws ConnectionException, AuthenticationException
    {
        AuthenticationInfo authInfo = getAuthenticationInfo();

        if ( authInfo == null )
        {
            throw new IllegalArgumentException( "Authentication Credentials cannot be null for FTP protocol" );
        }

        String username = authInfo.getUserName();

        String password = authInfo.getPassword();

        String host = getRepository().getHost();

        ftp = new FTPClient();

        ftp.addProtocolCommandListener( new PrintCommandListener( this ) );

        try
        {
            if ( getRepository().getPort() != WagonConstants.UNKNOWN_PORT )
            {
                ftp.connect( host, getRepository().getPort() );
            }
            else
            {
                ftp.connect( host );
            }

            // After connection attempt, you should check the reply code to
            // verify
            // success.
            int reply = ftp.getReplyCode();

            if ( !FTPReply.isPositiveCompletion( reply ) )
            {
                fireSessionConnectionRefused();

                fireSessionDisconnecting();

                ftp.disconnect();

                fireSessionDisconnected();

                throw new AuthenticationException( "FTP server refused connection." );
            }
        }
        catch ( IOException e )
        {
            if ( ftp.isConnected() )
            {
                try
                {
                    fireSessionError( e );

                    fireSessionDisconnecting();

                    ftp.disconnect();

                    fireSessionDisconnected();
                }
                catch ( IOException f )
                {
                    // do nothing
                }
            }

            throw new AuthenticationException( "Could not connect to server.", e );
        }

        try
        {
            if ( !ftp.login( username.trim(), password.trim() ) )
            {
                fireSessionConnectionRefused();

                throw new AuthenticationException( "Cannot login to remote system" );
            }

            fireSessionDebug( "Remote system is " + ftp.getSystemName() );

            // Set to binary mode.
            ftp.setFileType( FTP.BINARY_FILE_TYPE );

            // Use passive mode as default because most of us are
            // behind firewalls these days.
            // TODO [BP]: make optional based on a flag
            ftp.enterLocalPassiveMode();

            boolean dirChanged = ftp.changeWorkingDirectory( getRepository().getBasedir() );

            if ( !dirChanged )
            {
                throw new ConnectionException( "Required directory: '" + getRepository().getBasedir() + "' " +
                                               "is missing" );
            }
        }
        catch ( IOException e )
        {
            throw new ConnectionException( "Cannot login to remote system", e );
        }
    }

    protected void firePutCompleted( Resource resource, File file )
    {
        try
        {
            // TODO [BP]: verify the order is correct
            ftp.completePendingCommand();

            RepositoryPermissions permissions = repository.getPermissions();

            if ( permissions != null && permissions.getGroup() != null )
            {
                // ignore failures
                ftp.sendSiteCommand( "CHGRP " + permissions.getGroup() );
            }

            if ( permissions != null && permissions.getFileMode() != null )
            {
                // ignore failures
                ftp.sendSiteCommand( "CHMOD " + permissions.getFileMode() );
            }
        }
        catch ( IOException e )
        {
            // TODO: handle
            // michal I am not sure  what error means in that context
            // I think that we will be able to recover or simply we will fail later on
        }

        super.firePutCompleted( resource, file );
    }


    protected void fireGetCompleted( Resource resource, File localFile )
    {
        try
        {
            ftp.completePendingCommand();
        }
        catch ( IOException e )
        {
            // TODO: handle
            // michal I am not sure  what error means in that context
            // actually I am not even sure why we have to invoke that command
            // I think that we will be able to recover or simply we will fail later on
        }
        super.fireGetCompleted( resource, localFile );
    }

    public void closeConnection()
        throws ConnectionException
    {
        if ( ftp != null && ftp.isConnected() )
        {
            try
            {
                // This is a NPE rethink shutting down the streams
                ftp.disconnect();
            }
            catch ( IOException e )
            {
                throw new ConnectionException( "Failed to close connection to FTP repository", e );
            }
        }
    }


    public void fillOutputData( OutputData outputData )
        throws TransferFailedException
    {

        OutputStream os;

        Resource resource = outputData.getResource();

        RepositoryPermissions permissions = repository.getPermissions();

        try
        {
            String[] dirs = PathUtils.dirnames( resource.getName() );

            for ( int i = 0; i < dirs.length; i++ )
            {
                boolean dirChanged = ftp.changeWorkingDirectory( dirs[i] );

                if ( !dirChanged )
                {
                    // first, try to create it
                    boolean success = ftp.makeDirectory( dirs[i] );

                    if ( success )
                    {
                        if ( permissions != null && permissions.getGroup() != null )
                        {
                            // ignore failures
                            ftp.sendSiteCommand( "CHGRP " + permissions.getGroup() );
                        }

                        if ( permissions != null && permissions.getDirectoryMode() != null )
                        {
                            // ignore failures
                            ftp.sendSiteCommand( "CHMOD " + permissions.getDirectoryMode() );
                        }

                        dirChanged = ftp.changeWorkingDirectory( dirs[i] );
                    }
                }

                if ( !dirChanged )
                {
                    throw new TransferFailedException( "Unable to create directory " + dirs[i] );
                }
            }

            // we come back to orginal basedir so
            // FTP wagon is ready for next requests
            for ( int i = 0; i < dirs.length; i++ )
            {
                if ( !ftp.changeWorkingDirectory( ".." ) )
                {
                    throw new TransferFailedException( "Unable to return to the base directory" );
                }
            }

            os = ftp.storeFileStream( resource.getName() );

            if ( os == null )
            {
                String msg = "Cannot transfer resource:  '" + resource +
                    "' Output stream is null. FTP Server response: " + ftp.getReplyString();

                throw new TransferFailedException( msg );

            }

            fireTransferDebug( "resource = " + resource );

        }
        catch ( IOException e )
        {
            throw new TransferFailedException( "Error transferring over FTP", e );
        }

        outputData.setOutputStream( os );

    }

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    public void fillInputData( InputData inputData )
        throws TransferFailedException, ResourceDoesNotExistException
    {
        InputStream is;

        Resource resource = inputData.getResource();

        try
        {
            String[] dirs = PathUtils.dirnames( resource.getName() );

            for ( int i = 0; i < dirs.length; i++ )
            {
                boolean dirChanged = ftp.changeWorkingDirectory( dirs[i] );

                if ( !dirChanged )
                {
                    String msg = "Resource " + resource + " not found. Directory " + dirs[i] + " does not exist";

                    throw new ResourceDoesNotExistException( msg );
                }
            }

            FTPFile[] ftpFiles = ftp.listFiles( "" );

            long contenetLength = ftpFiles[0].getSize();

            //@todo check how it works! javadoc of common login says:
            // Returns the file timestamp. This usually the last modification time.
            //
            long lastModified = ftpFiles[0].getTimestamp().getTimeInMillis();

            resource.setContentLength( contenetLength );

            resource.setLastModified( lastModified );

            is = ftp.retrieveFileStream( PathUtils.filename( resource.getName() ) );

            for ( int i = 0; i < dirs.length; i++ )
            {
                ftp.changeWorkingDirectory( ".." );
            }
        }
        catch ( IOException e )
        {
            throw new TransferFailedException( "Error transferring file via FTP", e );
        }

        inputData.setInputStream( is );
    }


    public class PrintCommandListener
        implements ProtocolCommandListener
    {
        FtpWagon wagon;

        public PrintCommandListener( FtpWagon wagon )
        {
            this.wagon = wagon;
        }

        public void protocolCommandSent( ProtocolCommandEvent event )
        {
            wagon.fireSessionDebug( "Command sent: " + event.getMessage() );

        }

        public void protocolReplyReceived( ProtocolCommandEvent event )
        {
            wagon.fireSessionDebug( "Reply received: " + event.getMessage() );
        }
    }

    protected void fireSessionDebug( String msg )
    {
        super.fireSessionDebug( msg );
    }
}