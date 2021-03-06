package com.algolia.search.saas;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UTFDataFormatException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/*
 * Copyright (c) 2015 Algolia
 * http://www.algolia.com/
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

/**
 * Entry point in the Java API.
 * You should instantiate a Client object with your ApplicationID, ApiKey and Hosts
 * to start using Algolia Search API
 */
public class APIClient {
    private int httpSocketTimeoutMS = 30000;
    private int httpConnectTimeoutMS = 2000;
    private int httpSearchTimeoutMS = 5000;

    private final static String version;
    private final static String fallbackDomain;

    static {
        String tmp = "N/A";
        try {
            InputStream versionStream = APIClient.class.getResourceAsStream("/version.properties");
            if (versionStream != null) {
                BufferedReader versionReader = new BufferedReader(new InputStreamReader(versionStream));
                tmp = versionReader.readLine();
                versionReader.close();
            }
        } catch (IOException e) {
            // not fatal
        }
        version = tmp;

        // fallback domain should be algolia.net if Java <= 1.6 because no SNI support
        {
            String version = System.getProperty("java.version");
            int pos = version.indexOf('.');
            pos = version.indexOf('.', pos + 1);
            fallbackDomain = Double.parseDouble(version.substring(0, pos)) <= 1.6 ? "algolia.net" : "algolianet.com";
        }
    }

    private final String applicationID;
    private final String apiKey;
    private final List<String> buildHostsArray;
    private final List<String> queryHostsArray;
    private final HttpClient httpClient;
    private String forwardRateLimitAPIKey;
    private String forwardEndUserIP;
    private String forwardAdminAPIKey;
    private HashMap<String, String> headers;
    private final boolean verbose;
    private String userAgent;

    /**
     * Algolia Search initialization
     *
     * @param applicationID the application ID you have in your admin interface
     * @param apiKey        a valid API key for the service
     */
    public APIClient(String applicationID, String apiKey) {
        this(applicationID, apiKey, Arrays.asList(applicationID + "-1." + fallbackDomain,
                applicationID + "-2." + fallbackDomain,
                applicationID + "-3." + fallbackDomain));
        this.buildHostsArray.add(0, applicationID + ".algolia.net");
        this.queryHostsArray.add(0, applicationID + "-dsn.algolia.net");
    }

    /**
     * Algolia Search initialization
     *
     * @param applicationID the application ID you have in your admin interface
     * @param apiKey        a valid API key for the service
     * @param hostsArray    the list of hosts that you have received for the service
     */
    public APIClient(String applicationID, String apiKey, List<String> hostsArray) {
        this(applicationID, apiKey, hostsArray, hostsArray);
    }

    /**
     * Algolia Search initialization
     *
     * @param applicationID   the application ID you have in your admin interface
     * @param apiKey          a valid API key for the service
     * @param buildHostsArray the list of hosts that you have received for the service
     * @param queryHostsArray the list of hosts that you have received for the service
     */
    public APIClient(String applicationID, String apiKey, List<String> buildHostsArray, List<String> queryHostArray) {
        userAgent = "Algolia for Java " + version;
        verbose = System.getenv("VERBOSE") != null;
        forwardRateLimitAPIKey = forwardAdminAPIKey = forwardEndUserIP = null;
        if (applicationID == null || applicationID.length() == 0) {
            throw new RuntimeException("AlgoliaSearch requires an applicationID.");
        }
        this.applicationID = applicationID;
        if (apiKey == null || apiKey.length() == 0) {
            throw new RuntimeException("AlgoliaSearch requires an apiKey.");
        }
        this.apiKey = apiKey;
        if (buildHostsArray == null || buildHostsArray.size() == 0 || queryHostArray == null || queryHostArray.size() == 0) {
            throw new RuntimeException("AlgoliaSearch requires a list of hostnames.");
        }

        this.buildHostsArray = new ArrayList<String>(buildHostsArray);
        this.queryHostsArray = new ArrayList<String>(queryHostArray);
        httpClient = HttpClientBuilder.create().disableAutomaticRetries().useSystemProperties().build();
        headers = new HashMap<String, String>();
    }

    /**
     * Allow to modify the user-agent in order to add the user agent of the integration
     */
    public void setUserAgent(String agent, String agentVersion) {
        userAgent = String.format("Algolia for Java %s %s (%s)", version, agent, agentVersion);
    }

    /**
     * Allow to use IP rate limit when you have a proxy between end-user and Algolia.
     * This option will set the X-Forwarded-For HTTP header with the client IP and the X-Forwarded-API-Key with the API Key having rate limits.
     *
     * @param adminAPIKey     the admin API Key you can find in your dashboard
     * @param endUserIP       the end user IP (you can use both IPV4 or IPV6 syntax)
     * @param rateLimitAPIKey the API key on which you have a rate limit
     */
    public void enableRateLimitForward(String adminAPIKey, String endUserIP, String rateLimitAPIKey) {
        this.forwardAdminAPIKey = adminAPIKey;
        this.forwardEndUserIP = endUserIP;
        this.forwardRateLimitAPIKey = rateLimitAPIKey;
    }

    /**
     * Disable IP rate limit enabled with enableRateLimitForward() function
     */
    public void disableRateLimitForward() {
        forwardAdminAPIKey = forwardEndUserIP = forwardRateLimitAPIKey = null;
    }

    /**
     * Allow to set custom headers
     */
    public void setExtraHeader(String key, String value) {
        headers.put(key, value);
    }

    /**
     * Allow to set the timeout
     *
     * @param connectTimeout connection timeout in MS
     * @param readTimeout    socket timeout in MS
     */
    public void setTimeout(int connectTimeout, int readTimeout) {
        httpSocketTimeoutMS = readTimeout;
        httpConnectTimeoutMS = connectTimeout;
    }

    /**
     * List all existing indexes
     * return an JSON Object in the form:
     * { "items": [ {"name": "contacts", "createdAt": "2013-01-18T15:33:13.556Z"},
     * {"name": "notes", "createdAt": "2013-01-18T15:33:13.556Z"}]}
     */
    public JSONObject listIndexes() throws AlgoliaException {
        return getRequest("/1/indexes/", false);
    }

    /**
     * Delete an index
     *
     * @param indexName the name of index to delete
     *                  return an object containing a "deletedAt" attribute
     */
    public JSONObject deleteIndex(String indexName) throws AlgoliaException {
        try {
            return deleteRequest("/1/indexes/" + URLEncoder.encode(indexName, "UTF-8"), true);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e); // $COVERAGE-IGNORE$
        }
    }

    /**
     * Move an existing index.
     *
     * @param srcIndexName the name of index to copy.
     * @param dstIndexName the new index name that will contains a copy of srcIndexName (destination will be overriten if it already exist).
     */
    public JSONObject moveIndex(String srcIndexName, String dstIndexName) throws AlgoliaException {
        try {
            JSONObject content = new JSONObject();
            content.put("operation", "move");
            content.put("destination", dstIndexName);
            return postRequest("/1/indexes/" + URLEncoder.encode(srcIndexName, "UTF-8") + "/operation", content.toString(), true, false);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e); // $COVERAGE-IGNORE$
        } catch (JSONException e) {
            throw new AlgoliaException(e.getMessage()); // $COVERAGE-IGNORE$
        }
    }

    /**
     * Copy an existing index.
     *
     * @param srcIndexName the name of index to copy.
     * @param dstIndexName the new index name that will contains a copy of srcIndexName (destination will be overriten if it already exist).
     */
    public JSONObject copyIndex(String srcIndexName, String dstIndexName) throws AlgoliaException {
        try {
            JSONObject content = new JSONObject();
            content.put("operation", "copy");
            content.put("destination", dstIndexName);
            return postRequest("/1/indexes/" + URLEncoder.encode(srcIndexName, "UTF-8") + "/operation", content.toString(), true, false);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e); // $COVERAGE-IGNORE$
        } catch (JSONException e) {
            throw new AlgoliaException(e.getMessage()); // $COVERAGE-IGNORE$
        }
    }

    public enum LogType {
        /// all query logs
        LOG_QUERY,
        /// all build logs
        LOG_BUILD,
        /// all error logs
        LOG_ERROR,
        /// all logs
        LOG_ALL
    }

    /**
     * Return 10 last log entries.
     */
    public JSONObject getLogs() throws AlgoliaException {
        return getRequest("/1/logs", false);
    }

    /**
     * Return last logs entries.
     *
     * @param offset Specify the first entry to retrieve (0-based, 0 is the most recent log entry).
     * @param length Specify the maximum number of entries to retrieve starting at offset. Maximum allowed value: 1000.
     */
    public JSONObject getLogs(int offset, int length) throws AlgoliaException {
        return getLogs(offset, length, LogType.LOG_ALL);
    }

    /**
     * Return last logs entries.
     *
     * @param offset     Specify the first entry to retrieve (0-based, 0 is the most recent log entry).
     * @param length     Specify the maximum number of entries to retrieve starting at offset. Maximum allowed value: 1000.
     * @param onlyErrors Retrieve only logs with an httpCode different than 200 and 201
     */
    public JSONObject getLogs(int offset, int length, boolean onlyErrors) throws AlgoliaException {
        return getLogs(offset, length, onlyErrors ? LogType.LOG_ERROR : LogType.LOG_ALL);
    }

    /**
     * Return last logs entries.
     *
     * @param offset  Specify the first entry to retrieve (0-based, 0 is the most recent log entry).
     * @param length  Specify the maximum number of entries to retrieve starting at offset. Maximum allowed value: 1000.
     * @param logType Specify the type of log to retrieve
     */
    public JSONObject getLogs(int offset, int length, LogType logType) throws AlgoliaException {
        String type = null;
        switch (logType) {
            case LOG_BUILD:
                type = "build";
                break;
            case LOG_QUERY:
                type = "query";
                break;
            case LOG_ERROR:
                type = "error";
                break;
            case LOG_ALL:
                type = "all";
                break;
        }
        return getRequest("/1/logs?offset=" + offset + "&length=" + length + "&type=" + type, false);
    }

    /**
     * Get the index object initialized (no server call needed for initialization)
     *
     * @param indexName the name of index
     */
    public Index initIndex(String indexName) {
        return new Index(this, indexName);
    }

    /**
     * List all existing user keys with their associated ACLs
     */
    public JSONObject listUserKeys() throws AlgoliaException {
        return getRequest("/1/keys", false);
    }

    /**
     * Get ACL of a user key
     */
    public JSONObject getUserKeyACL(String key) throws AlgoliaException {
        return getRequest("/1/keys/" + key, false);
    }

    /**
     * Delete an existing user key
     */
    public JSONObject deleteUserKey(String key) throws AlgoliaException {
        return deleteRequest("/1/keys/" + key, true);
    }

    /**
     * Create a new user key
     *
     * @param params the list of parameters for this key. Defined by a JSONObject that
     *               can contains the following values:
     *               - acl: array of string
     *               - indices: array of string
     *               - validity: int
     *               - referers: array of string
     *               - description: string
     *               - maxHitsPerQuery: integer
     *               - queryParameters: string
     *               - maxQueriesPerIPPerHour: integer
     */
    public JSONObject addUserKey(JSONObject params) throws AlgoliaException {
        return postRequest("/1/keys", params.toString(), true, false);
    }

    /**
     * Create a new user key
     *
     * @param acls the list of ACL for this key. Defined by an array of strings that
     *             can contains the following values:
     *             - search: allow to search (https and http)
     *             - addObject: allows to add/update an object in the index (https only)
     *             - deleteObject : allows to delete an existing object (https only)
     *             - deleteIndex : allows to delete index content (https only)
     *             - settings : allows to get index settings (https only)
     *             - editSettings : allows to change index settings (https only)
     */
    public JSONObject addUserKey(List<String> acls) throws AlgoliaException {
        return addUserKey(acls, 0, 0, 0, null);
    }

    /**
     * Update a user key
     *
     * @param params the list of parameters for this key. Defined by a JSONObject that
     *               can contains the following values:
     *               - acl: array of string
     *               - indices: array of string
     *               - validity: int
     *               - referers: array of string
     *               - description: string
     *               - maxHitsPerQuery: integer
     *               - queryParameters: string
     *               - maxQueriesPerIPPerHour: integer
     */
    public JSONObject updateUserKey(String key, JSONObject params) throws AlgoliaException {
        return putRequest("/1/keys/" + key, params.toString(), true);
    }

    /**
     * Update a user key
     *
     * @param acls the list of ACL for this key. Defined by an array of strings that
     *             can contains the following values:
     *             - search: allow to search (https and http)
     *             - addObject: allows to add/update an object in the index (https only)
     *             - deleteObject : allows to delete an existing object (https only)
     *             - deleteIndex : allows to delete index content (https only)
     *             - settings : allows to get index settings (https only)
     *             - editSettings : allows to change index settings (https only)
     */
    public JSONObject updateUserKey(String key, List<String> acls) throws AlgoliaException {
        return updateUserKey(key, acls, 0, 0, 0, null);
    }

    /**
     * Create a new user key
     *
     * @param acls                   the list of ACL for this key. Defined by an array of strings that
     *                               can contains the following values:
     *                               - search: allow to search (https and http)
     *                               - addObject: allows to add/update an object in the index (https only)
     *                               - deleteObject : allows to delete an existing object (https only)
     *                               - deleteIndex : allows to delete index content (https only)
     *                               - settings : allows to get index settings (https only)
     *                               - editSettings : allows to change index settings (https only)
     * @param validity               the number of seconds after which the key will be automatically removed (0 means no time limit for this key)
     * @param maxQueriesPerIPPerHour Specify the maximum number of API calls allowed from an IP address per hour.  Defaults to 0 (no rate limit).
     * @param maxHitsPerQuery        Specify the maximum number of hits this API key can retrieve in one call. Defaults to 0 (unlimited)
     */
    public JSONObject addUserKey(List<String> acls, int validity, int maxQueriesPerIPPerHour, int maxHitsPerQuery) throws AlgoliaException {
        return addUserKey(acls, validity, maxQueriesPerIPPerHour, maxHitsPerQuery, null);
    }

    /**
     * Update a user key
     *
     * @param acls                   the list of ACL for this key. Defined by an array of strings that
     *                               can contains the following values:
     *                               - search: allow to search (https and http)
     *                               - addObject: allows to add/update an object in the index (https only)
     *                               - deleteObject : allows to delete an existing object (https only)
     *                               - deleteIndex : allows to delete index content (https only)
     *                               - settings : allows to get index settings (https only)
     *                               - editSettings : allows to change index settings (https only)
     * @param validity               the number of seconds after which the key will be automatically removed (0 means no time limit for this key)
     * @param maxQueriesPerIPPerHour Specify the maximum number of API calls allowed from an IP address per hour.  Defaults to 0 (no rate limit).
     * @param maxHitsPerQuery        Specify the maximum number of hits this API key can retrieve in one call. Defaults to 0 (unlimited)
     */
    public JSONObject updateUserKey(String key, List<String> acls, int validity, int maxQueriesPerIPPerHour, int maxHitsPerQuery) throws AlgoliaException {
        return updateUserKey(key, acls, validity, maxQueriesPerIPPerHour, maxHitsPerQuery, null);
    }

    /**
     * Create a new user key
     *
     * @param acls                   the list of ACL for this key. Defined by an array of strings that
     *                               can contains the following values:
     *                               - search: allow to search (https and http)
     *                               - addObject: allows to add/update an object in the index (https only)
     *                               - deleteObject : allows to delete an existing object (https only)
     *                               - deleteIndex : allows to delete index content (https only)
     *                               - settings : allows to get index settings (https only)
     *                               - editSettings : allows to change index settings (https only)
     * @param validity               the number of seconds after which the key will be automatically removed (0 means no time limit for this key)
     * @param maxQueriesPerIPPerHour Specify the maximum number of API calls allowed from an IP address per hour.  Defaults to 0 (no rate limit).
     * @param maxHitsPerQuery        Specify the maximum number of hits this API key can retrieve in one call. Defaults to 0 (unlimited)
     * @param indexes                the list of targeted indexes
     */
    public JSONObject addUserKey(List<String> acls, int validity, int maxQueriesPerIPPerHour, int maxHitsPerQuery, List<String> indexes) throws AlgoliaException {
        JSONArray array = new JSONArray(acls);
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("acl", array);
            jsonObject.put("validity", validity);
            jsonObject.put("maxQueriesPerIPPerHour", maxQueriesPerIPPerHour);
            jsonObject.put("maxHitsPerQuery", maxHitsPerQuery);
            if (indexes != null) {
                jsonObject.put("indexes", new JSONArray(indexes));
            }
        } catch (JSONException e) {
            throw new RuntimeException(e); // $COVERAGE-IGNORE$
        }
        return addUserKey(jsonObject);
    }

    /**
     * Update a user key
     *
     * @param acls                   the list of ACL for this key. Defined by an array of strings that
     *                               can contains the following values:
     *                               - search: allow to search (https and http)
     *                               - addObject: allows to add/update an object in the index (https only)
     *                               - deleteObject : allows to delete an existing object (https only)
     *                               - deleteIndex : allows to delete index content (https only)
     *                               - settings : allows to get index settings (https only)
     *                               - editSettings : allows to change index settings (https only)
     * @param validity               the number of seconds after which the key will be automatically removed (0 means no time limit for this key)
     * @param maxQueriesPerIPPerHour Specify the maximum number of API calls allowed from an IP address per hour.  Defaults to 0 (no rate limit).
     * @param maxHitsPerQuery        Specify the maximum number of hits this API key can retrieve in one call. Defaults to 0 (unlimited)
     * @param indexes                the list of targeted indexes
     */
    public JSONObject updateUserKey(String key, List<String> acls, int validity, int maxQueriesPerIPPerHour, int maxHitsPerQuery, List<String> indexes) throws AlgoliaException {
        JSONArray array = new JSONArray(acls);
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("acl", array);
            jsonObject.put("validity", validity);
            jsonObject.put("maxQueriesPerIPPerHour", maxQueriesPerIPPerHour);
            jsonObject.put("maxHitsPerQuery", maxHitsPerQuery);
            if (indexes != null) {
                jsonObject.put("indexes", new JSONArray(indexes));
            }
        } catch (JSONException e) {
            throw new RuntimeException(e); // $COVERAGE-IGNORE$
        }
        return updateUserKey(key, jsonObject);
    }

    /**
     * Generate a secured and public API Key from a list of tagFilters and an
     * optional user token identifying the current user
     *
     * @param privateApiKey your private API Key
     * @param tagFilters    the list of tags applied to the query (used as security)
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @deprecated Use `generateSecuredApiKey(String privateApiKey, Query query)` version
     */
    @Deprecated
    public String generateSecuredApiKey(String privateApiKey, String tagFilters) throws NoSuchAlgorithmException, InvalidKeyException {
        if (!tagFilters.contains("="))
            return generateSecuredApiKey(privateApiKey, new Query().setTagFilters(tagFilters), null);
        else {
            return Base64.encodeBase64String(String.format("%s%s", hmac(privateApiKey, tagFilters), tagFilters).getBytes(Charset.forName("UTF8")));
        }
    }

    /**
     * Generate a secured and public API Key from a query and an
     * optional user token identifying the current user
     *
     * @param privateApiKey your private API Key
     * @param query         contains the parameter applied to the query (used as security)
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     */
    public String generateSecuredApiKey(String privateApiKey, Query query) throws NoSuchAlgorithmException, InvalidKeyException {
        return generateSecuredApiKey(privateApiKey, query, null);
    }

    /**
     * Generate a secured and public API Key from a list of tagFilters and an
     * optional user token identifying the current user
     *
     * @param privateApiKey your private API Key
     * @param tagFilters    the list of tags applied to the query (used as security)
     * @param userToken     an optional token identifying the current user
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws AlgoliaException
     * @deprecated Use `generateSecuredApiKey(String privateApiKey, Query query, String userToken)` version
     */
    @Deprecated
    public String generateSecuredApiKey(String privateApiKey, String tagFilters, String userToken) throws NoSuchAlgorithmException, InvalidKeyException, AlgoliaException {
        if (!tagFilters.contains("="))
            return generateSecuredApiKey(privateApiKey, new Query().setTagFilters(tagFilters), userToken);
        else {
            if (userToken != null && userToken.length() > 0) {
                try {
                    tagFilters = String.format("%s%s%s", tagFilters, "&userToken=", URLEncoder.encode(userToken, "UTF-8"));
                } catch (UnsupportedEncodingException e) {
                    throw new AlgoliaException(e.getMessage());
                }
            }
            return Base64.encodeBase64String(String.format("%s%s", hmac(privateApiKey, tagFilters), tagFilters).getBytes(Charset.forName("UTF8")));
        }
    }

    /**
     * Generate a secured and public API Key from a query and an
     * optional user token identifying the current user
     *
     * @param privateApiKey your private API Key
     * @param query         contains the parameter applied to the query (used as security)
     * @param userToken     an optional token identifying the current user
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     */
    public String generateSecuredApiKey(String privateApiKey, Query query, String userToken) throws NoSuchAlgorithmException, InvalidKeyException {
        if (userToken != null && userToken.length() > 0) {
            query.setUserToken(userToken);
        }
        String queryStr = query.getQueryString();
        String key = hmac(privateApiKey, queryStr);

        return Base64.encodeBase64String(String.format("%s%s", key, queryStr).getBytes(Charset.forName("UTF8")));

    }

    static String hmac(String key, String msg) {
        Mac hmac;
        try {
            hmac = Mac.getInstance("HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
        try {
            hmac.init(new SecretKeySpec(key.getBytes(), "HmacSHA256"));
        } catch (InvalidKeyException e) {
            throw new Error(e);
        }
        byte[] rawHmac = hmac.doFinal(msg.getBytes());
        byte[] hexBytes = new Hex().encode(rawHmac);
        return new String(hexBytes);
    }

    private static enum Method {
        GET, POST, PUT, DELETE, OPTIONS, TRACE, HEAD;
    }

    protected JSONObject getRequest(String url, boolean search) throws AlgoliaException {
        return _request(Method.GET, url, null, false, search);
    }

    protected JSONObject deleteRequest(String url, boolean build) throws AlgoliaException {
        return _request(Method.DELETE, url, null, build, false);
    }

    protected JSONObject postRequest(String url, String obj, boolean build, boolean search) throws AlgoliaException {
        return _request(Method.POST, url, obj, build, search);
    }

    protected JSONObject putRequest(String url, String obj, boolean build) throws AlgoliaException {
        return _request(Method.PUT, url, obj, build, false);
    }

    private JSONObject _requestByHost(HttpRequestBase req, String host, String url, String json, HashMap<String, String> errors, boolean searchTimeout) throws AlgoliaException {
        req.reset();

        // set URL
        try {
            req.setURI(new URI("https://" + host + url));
        } catch (URISyntaxException e) {
            // never reached
            throw new IllegalStateException(e);
        }

        // set auth headers
        req.setHeader("Accept-Encoding", "gzip");
        req.setHeader("X-Algolia-Application-Id", this.applicationID);
        if (forwardAdminAPIKey == null) {
            req.setHeader("X-Algolia-API-Key", this.apiKey);
        } else {
            req.setHeader("X-Algolia-API-Key", this.forwardAdminAPIKey);
            req.setHeader("X-Forwarded-For", this.forwardEndUserIP);
            req.setHeader("X-Forwarded-API-Key", this.forwardRateLimitAPIKey);
        }
        for (Entry<String, String> entry : headers.entrySet()) {
            req.setHeader(entry.getKey(), entry.getValue());
        }

        // set user agent
        req.setHeader("User-Agent", userAgent);

        // set JSON entity
        if (json != null) {
            if (!(req instanceof HttpEntityEnclosingRequestBase)) {
                throw new IllegalArgumentException("Method " + req.getMethod() + " cannot enclose entity");
            }
            req.setHeader("Content-type", "application/json");
            try {
                StringEntity se = new StringEntity(json, "UTF-8");
                se.setContentEncoding(new BasicHeader(HTTP.CONTENT_TYPE, "application/json"));
                ((HttpEntityEnclosingRequestBase) req).setEntity(se);
            } catch (Exception e) {
                throw new AlgoliaException("Invalid JSON Object: " + json); // $COVERAGE-IGNORE$
            }
        }

        RequestConfig config = RequestConfig.custom()
                .setSocketTimeout(searchTimeout ? httpSearchTimeoutMS : httpSocketTimeoutMS)
                .setConnectTimeout(httpConnectTimeoutMS)
                .setConnectionRequestTimeout(httpConnectTimeoutMS)
                .build();
        req.setConfig(config);

        HttpResponse response;
        try {
            response = httpClient.execute(req);
        } catch (IOException e) {
            // on error continue on the next host
            if (verbose) {
                System.out.println(String.format("%s: %s=%s", host, e.getClass().getName(), e.getMessage()));
            }
            errors.put(host, String.format("%s=%s", e.getClass().getName(), e.getMessage()));
            return null;
        }
        try {
            int code = response.getStatusLine().getStatusCode();
            if (code / 100 == 4) {
                String message = "";
                try {
                    message = EntityUtils.toString(response.getEntity());
                } catch (ParseException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (code == 400) {
                    throw new AlgoliaException(code, message.length() > 0 ? message : "Bad request");
                } else if (code == 403) {
                    throw new AlgoliaException(code, message.length() > 0 ? message : "Invalid Application-ID or API-Key");
                } else if (code == 404) {
                    throw new AlgoliaException(code, message.length() > 0 ? message : "Resource does not exist");
                } else {
                    throw new AlgoliaException(code, message.length() > 0 ? message : "Error");
                }
            }
            if (code / 100 != 2) {
                try {
                    if (verbose) {
                        System.out.println(String.format("%s: %s", host, EntityUtils.toString(response.getEntity())));
                    }
                    errors.put(host, EntityUtils.toString(response.getEntity()));
                } catch (IOException e) {
                    if (verbose) {
                        System.out.println(String.format("%s: %s", host, String.valueOf(code)));
                    }
                    errors.put(host, String.valueOf(code));
                }
                // KO, continue
                return null;
            }
            try {
                InputStream istream = response.getEntity().getContent();
                String encoding = response.getEntity().getContentEncoding() != null ? response.getEntity().getContentEncoding().getValue() : null;
                if (encoding != null && encoding.contains("gzip")) {
                    istream = new GZIPInputStream(istream);
                }
                InputStreamReader is = new InputStreamReader(istream, "UTF-8");
                StringBuilder jsonRaw = new StringBuilder();
                char[] buffer = new char[4096];
                int read = 0;
                while ((read = is.read(buffer)) > 0) {
                    jsonRaw.append(buffer, 0, read);
                }
                is.close();
                return new JSONObject(jsonRaw.toString());
            } catch (IOException e) {
                if (verbose) {
                    System.out.println(String.format("%s: %s=%s", host, e.getClass().getName(), e.getMessage()));
                }
                errors.put(host, String.format("%s=%s", e.getClass().getName(), e.getMessage()));
                return null;
            } catch (JSONException e) {
                throw new AlgoliaException("JSON decode error:" + e.getMessage());
            }
        } finally {
            req.releaseConnection();
        }
    }

    private JSONObject _request(Method m, String url, String json, boolean build, boolean search) throws AlgoliaException {
        HttpRequestBase req;
        switch (m) {
            case DELETE:
                req = new HttpDelete();
                break;
            case GET:
                req = new HttpGet();
                break;
            case POST:
                req = new HttpPost();
                break;
            case PUT:
                req = new HttpPut();
                break;
            default:
                throw new IllegalArgumentException("Method " + m + " is not supported");
        }
        HashMap<String, String> errors = new HashMap<String, String>();
        List<String> hosts = build ? this.buildHostsArray : this.queryHostsArray;

        // for each host
        for (int i = 0; i < hosts.size(); ++i) {
            String host = hosts.get(i);
            JSONObject res = _requestByHost(req, host, url, json, errors, search);
            if (res != null) {
                return res;
            }
        }
        StringBuilder builder = new StringBuilder("Hosts unreachable: ");
        Boolean first = true;
        for (Map.Entry<String, String> entry : errors.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(entry.toString());
            first = false;
        }
        throw new AlgoliaException(builder.toString());
    }

    static public class IndexQuery {
        private String index;
        private Query query;

        public IndexQuery(String index, Query q) {
            this.index = index;
            this.query = q;
        }

        public String getIndex() {
            return index;
        }

        public void setIndex(String index) {
            this.index = index;
        }

        public Query getQuery() {
            return query;
        }

        public void setQuery(Query query) {
            this.query = query;
        }
    }

    /**
     * This method allows to query multiple indexes with one API call
     */
    public JSONObject multipleQueries(List<IndexQuery> queries) throws AlgoliaException {
        return multipleQueries(queries, "none");
    }

    public JSONObject multipleQueries(List<IndexQuery> queries, String strategy) throws AlgoliaException {
        try {
            JSONArray requests = new JSONArray();
            for (IndexQuery indexQuery : queries) {
                String paramsString = indexQuery.getQuery().getQueryString();
                requests.put(new JSONObject().put("indexName", indexQuery.getIndex()).put("params", paramsString));
            }
            JSONObject body = new JSONObject().put("requests", requests);
            return postRequest("/1/indexes/*/queries?strategy=" + strategy, body.toString(), false, true);
        } catch (JSONException e) {
            new AlgoliaException(e.getMessage());
        }
        return null;
    }

    /**
     * Custom batch
     *
     * @param actions the array of actions
     * @throws AlgoliaException
     */
    public JSONObject batch(JSONArray actions) throws AlgoliaException {
        try {
            JSONObject content = new JSONObject();
            content.put("requests", actions);
            return postRequest("/1/indexes/*/batch", content.toString(), true, false);
        } catch (JSONException e) {
            throw new AlgoliaException(e.getMessage());
        }
    }

    /**
     * Custom batch
     *
     * @param actions the array of actions
     * @throws AlgoliaException
     */
    public JSONObject batch(List<JSONObject> actions) throws AlgoliaException {
        try {
            JSONObject content = new JSONObject();
            content.put("requests", actions);
            return postRequest("/1/indexes/*/batch", content.toString(), true, false);
        } catch (JSONException e) {
            throw new AlgoliaException(e.getMessage());
        }
    }

}
