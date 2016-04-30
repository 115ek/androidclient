/*
 * Kontalk Android client
 * Copyright (C) 2016 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.upload;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.net.ssl.HttpsURLConnection;

import org.apache.http.conn.ssl.AllowAllHostnameVerifier;

import android.content.Context;
import android.net.Uri;

import org.kontalk.client.ClientHTTPConnection;
import org.kontalk.service.ProgressListener;
import org.kontalk.util.Preferences;
import org.kontalk.util.ProgressInputStreamEntity;


/**
 * Simple HTTP upload via PUT.
 * @author Daniele Ricci
 */
public class HTPPFileUploadConnection implements UploadConnection {

    private final Context mContext;
    private final String mUrl;

    private HttpsURLConnection currentRequest;

    private final static int CONNECT_TIMEOUT = 15000;
    private final static int READ_TIMEOUT = 40000;

    public HTPPFileUploadConnection(Context context, String url) {
        mContext = context;
        mUrl = url;
    }

    @Override
    public void abort() {
        try {
            currentRequest.disconnect();
        }
        catch (Exception ignored) {
        }
    }

    @Override
    public String upload(Uri uri, long length, String mime, boolean encrypt, String to, ProgressListener listener) throws IOException {
        InputStream inMessage = null;
        try {
            inMessage = mContext.getContentResolver().openInputStream(uri);

            // http request!
            boolean acceptAnyCertificate = Preferences.getAcceptAnyCertificate(mContext);
            currentRequest = prepareMessage(length, mime, true, acceptAnyCertificate);

            // execute!
            ProgressInputStreamEntity entity = new ProgressInputStreamEntity(inMessage, this, listener);
            entity.writeTo(currentRequest.getOutputStream());

            if (currentRequest.getResponseCode() != 200)
                throw new IOException(currentRequest.getResponseCode() + " " + currentRequest.getResponseMessage());

            // no media url returned
            return null;
        }
        catch (Exception e) {
            throw innerException("upload error", e);
        }
        finally {
            currentRequest = null;
            if (inMessage != null) {
                try {
                    inMessage.close();
                }
                catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    private IOException innerException(String detail, Throwable cause) {
        IOException ie = new IOException(detail);
        ie.initCause(cause);
        return ie;
    }

    private void setupClient(HttpsURLConnection conn, long length, String mime, boolean encrypted, boolean acceptAnyCertificate)
        throws CertificateException, UnrecoverableKeyException,
        NoSuchAlgorithmException, KeyStoreException,
        KeyManagementException, NoSuchProviderException,
        IOException {

        conn.setSSLSocketFactory(ClientHTTPConnection.setupSSLSocketFactory(mContext,
            null, null, acceptAnyCertificate));
        if (acceptAnyCertificate)
            conn.setHostnameVerifier(new AllowAllHostnameVerifier());
        conn.setRequestProperty("Content-Type", mime != null ? mime
            : "application/octet-stream");
        // bug caused by Lighttpd
        //conn.setRequestProperty("Expect", "100-continue");

        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setRequestProperty("Content-Length", String.valueOf(length));
        conn.setRequestMethod("PUT");
    }

    /** A message posting method. */
    private HttpsURLConnection prepareMessage(long length, String mime, boolean encrypted, boolean acceptAnyCertificate)
            throws IOException {

        // create uri
        HttpsURLConnection conn = (HttpsURLConnection) new URL(mUrl).openConnection();
        try {
            setupClient(conn, length, mime, encrypted, acceptAnyCertificate);
        }
        catch (Exception e) {
            throw new IOException("error setting up SSL connection", e);
        }

        return conn;
    }

}
