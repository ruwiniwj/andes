/*
 *
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
 *
 */
package org.wso2.andes.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.wso2.andes.jms.BrokerDetails;
import org.wso2.andes.url.URLHelper;
import org.wso2.andes.url.URLSyntaxException;

public class AMQBrokerDetails implements BrokerDetails
{

    /**
     * URL pattern
     */
    private static final Pattern URL_PATTERN = Pattern.compile("([a-z]+://.*)|([a-z]+:/.*)");
    /**
     * Password pattern in JMS URL
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("transport.jms.Password=([^/&]+)(&|$)");

    private static final Pattern KEYSTORE_PWD_PATTERN = Pattern.compile("key_store_password=([^/&]+)(&|$)");
    private static final Pattern TRUSTSTORE_PWD_PATTERN = Pattern.compile("trust_store_password=([^/&]+)(&|$)");
    /**
     * Security credential pattern in JMS URL
     */
    private static final Pattern SECURITY_CREDENTIAL_PATTERN = Pattern
            .compile("java.naming.security.credentials=([^/&]+)(&|$)");
    private static final String URL_PASSWORD_SUB_STRING = "transport.jms.Password=***&";
    private static final String URL_CREDENTIALS_SUB_STRING = "java.naming.security.credentials=***&";
    private static final String KEYSTORE_PWD_SUBSTRING = "key_store_password=***&";
    private static final String TRUSTSTORE_PWD_SUBSTRING = "trust_store_password=***&";
    private static final String PATTERN_END = "&";
    private static final String MASKING_STRING = "***";

    private String _host;
    private int _port;
    private String _transport;

    private Map<String, String> _options = new HashMap<String, String>();

    private SSLConfiguration _sslConfiguration;

    public AMQBrokerDetails(){}
    
    public AMQBrokerDetails(String url) throws URLSyntaxException
    {        
      
        // URL should be of format tcp://host:port?option='value',option='value'
        try
        {
            URI connection = new URI(url);

            String transport = connection.getScheme();

            // Handles some defaults to minimise changes to existing broker URLS e.g. localhost
            if (transport != null)
            {
                //todo this list of valid transports should be enumerated somewhere
                if (!(transport.equalsIgnoreCase(BrokerDetails.TCP)))
                {
                    if (transport.equalsIgnoreCase("localhost"))
                    {
                        connection = new URI(DEFAULT_TRANSPORT + "://" + url);
                        transport = connection.getScheme();
                    }
                    else
                    {
                        if (url.charAt(transport.length()) == ':' && url.charAt(transport.length() + 1) != '/')
                        {
                            //Then most likely we have a host:port value
                            connection = new URI(DEFAULT_TRANSPORT + "://" + url);
                            transport = connection.getScheme();
                        }
                        else
                        {
                            throw URLHelper.parseError(0, transport.length(), "Unknown transport", url);
                        }
                    }
                }
                else if (url.indexOf("//") == -1)
                {
                    throw URLHelper.parseError(transport.length()+1, 1, "Missing '//' after the transport In broker " +
                            "URL", url);
                }
            }
            else
            {
                //Default the transport
                connection = new URI(DEFAULT_TRANSPORT + "://" + url);
                transport = connection.getScheme();
            }

            if (transport == null)
            {
                throw URLHelper.parseError(-1, "Unknown transport in broker URL:'"
                        + url + "' Format: " + URL_FORMAT_EXAMPLE, "");
            }

            setTransport(transport);

            String host = connection.getHost();

            // Fix for Java 1.5
            if (host == null)
            {
                host = "";
                
                String auth = connection.getAuthority();
                if (auth != null)
                {
                    // contains both host & port myhost:5672                
                    if (auth.contains(":"))
                    {
                        host = auth.substring(0,auth.indexOf(":"));
                    }
                    else
                    {
                        host = auth;
                    }
                }

            }

            setHost(host);

            int port = connection.getPort();

            if (port == -1)
            {
                // Fix for when there is port data but it is not automatically parseable by getPort().
                String auth = connection.getAuthority();

                if (auth != null && auth.contains(":"))
                {
                    int start = auth.indexOf(":") + 1;
                    int end = start;
                    boolean looking = true;
                    boolean found = false;
                    // Throw an URL exception if the port number is not specified
                    if (start == auth.length())
                    {
                        throw URLHelper.parseError(connection.toString().indexOf(auth) + end - 1,
                                connection.toString().indexOf(auth) + end, "Port number must be specified",
                                connection.toString());
                    }
                    //Walk the authority looking for a port value.
                    while (looking)
                    {
                        try
                        {
                            end++;
                            Integer.parseInt(auth.substring(start, end));

                            if (end >= auth.length())
                            {
                                looking = false;
                                found = true;
                            }
                        }
                        catch (NumberFormatException nfe)
                        {
                            looking = false;
                        }

                    }
                    if (found)
                    {
                        setPort(Integer.parseInt(auth.substring(start, end)));
                    }
                    else
                    {
                        throw URLHelper.parseError(connection.toString().indexOf(connection.getAuthority()) + end - 1,
                                             "Illegal character in port number", connection.toString());
                    }

                }
                else
                {
                    setPort(DEFAULT_PORT);
                }
            }
            else
            {
                setPort(port);
            }

            String queryString = connection.getQuery();

            URLHelper.parseOptions(_options, queryString);

            //Fragment is #string (not used)
        }
        catch (URISyntaxException uris)
        {
            if (uris instanceof URLSyntaxException)
            {
                throw(URLSyntaxException) uris;
            }

            throw URLHelper.parseError(uris.getIndex(), uris.getReason(), uris.getInput());
        }
    }

    public AMQBrokerDetails(String host, int port, SSLConfiguration sslConfiguration)
    {
        _host = host;
        _port = port;
        _sslConfiguration = sslConfiguration;
    }

    public String getHost()
    {
        return _host;
    }

    public void setHost(String _host)
    {
        this._host = _host;
    }

    public int getPort()
    {
        return _port;
    }

    public void setPort(int _port)
    {
        this._port = _port;
    }

    public String getTransport()
    {
        return _transport;
    }

    public void setTransport(String _transport)
    {
        this._transport = _transport;
    }


    public String getProperty(String key)
    {
        return _options.get(key);
    }

    public void setProperty(String key, String value)
    {
        _options.put(key, value);
    }

    public long getTimeout()
    {
        if (_options.containsKey(OPTIONS_CONNECT_TIMEOUT))
        {
            try
            {
                return Long.parseLong(_options.get(OPTIONS_CONNECT_TIMEOUT));
            }
            catch (NumberFormatException nfe)
            {
                //Do nothing as we will use the default below.
            }
        }

        return BrokerDetails.DEFAULT_CONNECT_TIMEOUT;
    }
    
    public boolean getBooleanProperty(String propName)
    {
        if (_options.containsKey(propName))
        {
            return Boolean.parseBoolean(_options.get(propName));
        }

        return false;
    }    

    public void setTimeout(long timeout)
    {
        setProperty(OPTIONS_CONNECT_TIMEOUT, Long.toString(timeout));
    }

    public SSLConfiguration getSSLConfiguration()
    {
        return _sslConfiguration;
    }

    public void setSSLConfiguration(SSLConfiguration sslConfig)
    {
        _sslConfiguration = sslConfig;
    }

    public String toString()
    {
        StringBuffer sb = new StringBuffer();

        sb.append(_transport);
        sb.append("://");
        sb.append(_host);
        sb.append(':');
        sb.append(_port);

        sb.append(printOptionsURL());

        return maskURLPasswordAndCredentials(sb.toString());
    }

    public boolean equals(Object o)
    {
        if (!(o instanceof BrokerDetails))
        {
            return false;
        }

        BrokerDetails bd = (BrokerDetails) o;

        return _host.equalsIgnoreCase(bd.getHost()) &&
               (_port == bd.getPort()) &&
               _transport.equalsIgnoreCase(bd.getTransport()) &&
               compareSSLConfigurations(bd.getSSLConfiguration());
        //todo do we need to compare all the options as well?
    }

    @Override
    public int hashCode()
    {
        int result = _host != null ? _host.hashCode() : 0;
        result = 31 * result + _port;
        result = 31 * result + (_transport != null ? _transport.hashCode() : 0);
        return result;
    }

    private String printOptionsURL()
    {
        StringBuffer optionsURL = new StringBuffer();

        optionsURL.append('?');

        if (!(_options.isEmpty()))
        {

            for (String key : _options.keySet())
            {
                optionsURL.append(key);

                optionsURL.append("='");

                optionsURL.append(_options.get(key));

                optionsURL.append("'");

                optionsURL.append(URLHelper.DEFAULT_OPTION_SEPERATOR);
            }
        }

        //removeKey the extra DEFAULT_OPTION_SEPERATOR or the '?' if there are no options
        optionsURL.deleteCharAt(optionsURL.length() - 1);

        return optionsURL.toString();
    }

    // Do we need to do a more in-depth comparison?
    private boolean compareSSLConfigurations(SSLConfiguration other)
    {
        boolean retval = false;
        if (_sslConfiguration == null &&
                other == null)
        {
            retval = true;
        }
        else if (_sslConfiguration != null &&
                other != null)
        {
            retval = true;
        }

        return retval;
    }

    public static String checkTransport(String broker)
    {
        if ((!broker.contains("://")))
        {
            return "tcp://" + broker;
        }
        else
        {
            return broker;
        }
    }

    public Map<String, String> getProperties()
    {
        return _options;
    }

    public void setProperties(Map<String, String> props)
    {
        _options = props;
    }

    /**
     * Mask the password and Security credentials of the connection url.
     *
     * @param url the actual url
     * @return the masked url
     */
    public String maskURLPasswordAndCredentials(String url) {

        final Matcher urlMatcher = URL_PATTERN.matcher(url);
        if (urlMatcher.find()) {
            final Matcher credentialMatcher = SECURITY_CREDENTIAL_PATTERN.matcher(url);
            String maskurl = credentialMatcher.replaceFirst(URL_CREDENTIALS_SUB_STRING);
            final Matcher pwdMatcher = PASSWORD_PATTERN.matcher(maskurl);
            maskurl = pwdMatcher.replaceAll(URL_PASSWORD_SUB_STRING);
            final Matcher keyStorePwdMatcher = KEYSTORE_PWD_PATTERN.matcher(maskurl);
            maskurl = keyStorePwdMatcher.replaceAll(KEYSTORE_PWD_SUBSTRING);
            final Matcher trustStorePwdMatcher = TRUSTSTORE_PWD_PATTERN.matcher(maskurl);
            maskurl = trustStorePwdMatcher.replaceAll(TRUSTSTORE_PWD_SUBSTRING);
            if (maskurl.endsWith(PATTERN_END)) {
                int end = maskurl.lastIndexOf(PATTERN_END);
                return maskurl.substring(0, end);
            }

            return maskurl;
        }
        return url;
    }
}
