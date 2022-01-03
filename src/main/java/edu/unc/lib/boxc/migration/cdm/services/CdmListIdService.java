/**
 * Copyright 2008 The University of North Carolina at Chapel Hill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.unc.lib.boxc.migration.cdm.services;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.unc.lib.boxc.common.util.URIUtil;
import edu.unc.lib.boxc.migration.cdm.exceptions.MigrationException;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.services.export.ExportStateService;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static edu.unc.lib.boxc.migration.cdm.services.export.ExportState.ProgressState;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Service for listing CDM object IDs
 * @author krwong
 */
public class CdmListIdService {
    private static final Logger log = getLogger(CdmListIdService.class);
    public static final String CDM_QUERY_BASE = "dmwebservices/index.php?q=dmQuery/";

    private MigrationProject project;
    private CloseableHttpClient httpClient;
    private String cdmBaseUri;
    private ExportStateService exportStateService;

    private int pageSize = 1000; //set default page size

    /**
     * Get the total number of object IDs for the given collection
     * @throw IOException
     * @return
     */
    private int getTotalObjects() throws IOException {
        String collectionId = project.getProjectProperties().getCdmCollectionId();
        String totalObjectsUrl = CDM_QUERY_BASE + collectionId
                + "/0/dmrecord/dmrecord/1/0/1/0/0/0/0/json";
        String totalUri = URIUtil.join(cdmBaseUri, totalObjectsUrl);

        ObjectMapper mapper = new ObjectMapper();
        HttpGet getMethod = new HttpGet(totalUri);
        try (CloseableHttpResponse resp = httpClient.execute(getMethod)) {
            String body = IOUtils.toString(resp.getEntity().getContent(), ISO_8859_1);
            if (resp.getStatusLine().getStatusCode() != 200) {
                throw new MigrationException("Request to retrieve object count " + totalUri
                        + " failed with status " + resp.getStatusLine().getStatusCode() + ": " + body);
            }
            if (body.contains("Error looking up collection")) {
                throw new MigrationException("No collection with ID '" + collectionId
                        + "' found on server at " + totalUri);
            }
            JsonParser parser = mapper.getFactory().createParser(body);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new MigrationException("Unexpected response from URL " + totalUri
                        + "\nIt must be a JSON object, please check the response.");
            }
                ObjectNode entryNode = mapper.readTree(parser);
                JsonNode pager = entryNode.get("pager");
                return pager.get("total").asInt();
        } catch (JsonParseException e) {
            throw new MigrationException("Failed to parse response from URL " + totalUri
                    + ": " + e.getMessage());
        }
    }

    /**
     * Page through the results
     * @param startIndex
     * @param total
     * @return
     */
    private List<String> pagingResults(int startIndex, int total) {
        int totalRecords = total;
        int startPage = startIndex / pageSize;
        int maxPages = totalRecords / pageSize;

        List<String> urls = new ArrayList<>();

        for (int page = startPage; page <= maxPages; page++) {
            int recordNum = (page * pageSize) + 1;
            String cdmPageUrl = CDM_QUERY_BASE
                    + project.getProjectProperties().getCdmCollectionId()
                    + "/0/dmrecord/dmrecord/" + pageSize + "/" + recordNum + "/1/0/0/0/0/json";
            String pageUrl = URIUtil.join(cdmBaseUri, cdmPageUrl);
            urls.add(pageUrl);
        }
        return urls;
    }

    /**
     * Parse json for object IDs
     * @param objectUri
     * @throw IOException
     * @return
     */
    private List<String> parseJson(String objectUri) throws IOException {
        String collectionId = project.getProjectProperties().getCdmCollectionId();

        List<String> objectIds = new ArrayList<>();

        ObjectMapper mapper = new ObjectMapper();
        HttpGet getMethod = new HttpGet(objectUri);
        log.debug("Requesting object list from URI: {}", objectUri);
        try (CloseableHttpResponse resp = httpClient.execute(getMethod)) {
            String body = IOUtils.toString(resp.getEntity().getContent(), ISO_8859_1);
            if (resp.getStatusLine().getStatusCode() != 200) {
                throw new MigrationException("Request to retrieve object listing " + objectUri
                        + " failed with status " + resp.getStatusLine().getStatusCode() + ": " + body);
            }
            if (body.contains("Error looking up collection")) {
                throw new MigrationException("No collection with ID '" + collectionId
                        + "' found on server at " + objectUri);
            }
            JsonParser parser = mapper.getFactory().createParser(body);
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                ObjectNode entryNode = mapper.readTree(parser);
                ArrayNode records = (ArrayNode)entryNode.get("records");
                for (JsonNode record : records) {
                    String objectId = record.get("dmrecord").asText();
                    objectIds.add(objectId);
                }
            }
            if (objectIds.isEmpty()) {
                throw new MigrationException("Retrieved no object ids for listing request: " + objectUri);
            }
        } catch (JsonParseException e) {
            throw new MigrationException("Failed to parse response from URL " + objectUri
                    + ": " + e.getMessage());
        }
        return objectIds;
    }

    /**
     * List all exported CDM records for this project
     * @return
     */
    public List<String> listAllCdmIds() throws IOException {
        // Get total number of objects either from cdm or from resumption data
        int totalObjects;
        if (exportStateService.isResuming()) {
            totalObjects = exportStateService.getState().getTotalObjects();
        } else {
            totalObjects = getTotalObjects();
            exportStateService.objectCountCompleted(totalObjects);
        }

        // If resuming, then start from the already retrieved ids, otherwise start from an empty list
        List<String> allObjectIds;
        if (exportStateService.isResuming()) {
            allObjectIds = exportStateService.retrieveObjectIds();
        } else {
            allObjectIds = new ArrayList<>();
        }
        // If we have not retrieved all the ids, then start retrieving the remaining ids
        if (allObjectIds.size() < totalObjects) {
            if (exportStateService.isResuming()) {
                exportStateService.assertState(ProgressState.LISTING_OBJECTS);
            } else {
                exportStateService.transitionToListing(pageSize);
            }

            List<String> pageUrls = pagingResults(allObjectIds.size(), totalObjects);
            for (String url : pageUrls) {
                List<String> objectIds = parseJson(url);
                exportStateService.registerObjectIds(objectIds);
                allObjectIds.addAll(objectIds);
            }
            exportStateService.listingComplete();
        }

        return allObjectIds;
    }

    public void setHttpClient(CloseableHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void setCdmBaseUri(String cdmBaseUri) {
        this.cdmBaseUri = cdmBaseUri;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public void setProject(MigrationProject project) {
        this.project = project;
    }

    public void setExportStateService(ExportStateService exportStateService) {
        this.exportStateService = exportStateService;
    }
}

