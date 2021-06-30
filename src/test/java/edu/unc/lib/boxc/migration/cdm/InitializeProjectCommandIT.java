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
package edu.unc.lib.boxc.migration.cdm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.github.tomakehurst.wiremock.junit.WireMockRule;

import edu.unc.lib.boxc.migration.cdm.model.CdmFieldInfo;
import edu.unc.lib.boxc.migration.cdm.model.CdmFieldInfo.CdmFieldEntry;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProjectProperties;
import edu.unc.lib.boxc.migration.cdm.services.CdmFieldService;
import edu.unc.lib.boxc.migration.cdm.services.MigrationProjectFactory;

/**
 * @author bbpennel
 */
public class InitializeProjectCommandIT extends AbstractCommandIT {
    private final static String COLLECTION_ID = "my_coll";

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort());

    private CdmFieldService fieldService;
    private String cdmBaseUrl;

    @Before
    public void setUp() throws Exception {
        fieldService = new CdmFieldService();

        cdmBaseUrl = "http://localhost:" + wireMockRule.port();
        String validRespBody = IOUtils.toString(this.getClass().getResourceAsStream("/cdm_fields_resp.json"),
                StandardCharsets.UTF_8);

        stubFor(get(urlMatching("/.*")).atPriority(5)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("Error looking up collection /collid<br>")));

        stubFor(get(urlEqualTo("/" + CdmFieldService.getFieldInfoUrl(COLLECTION_ID)))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/json")
                        .withBody(validRespBody)));
    }

    @Test
    public void initValidProjectTest() throws Exception {
        String[] initArgs = new String[] {
                "-w", baseDir.toString(),
                "init",
                "--cdm-url", cdmBaseUrl,
                "-p", COLLECTION_ID };
        executeExpectSuccess(initArgs);

        MigrationProject project = MigrationProjectFactory.loadMigrationProject(baseDir.resolve(COLLECTION_ID));
        MigrationProjectProperties properties = project.getProjectProperties();
        assertPropertiesSet(properties, COLLECTION_ID, COLLECTION_ID);

        assertTrue("Description folder not created", Files.exists(project.getDescriptionsPath()));

        assertCdmFieldsPresent(project);
    }

    @Test
    public void initValidProjectUsingCurrentDirTest() throws Exception {
        Path projDir = baseDir.resolve("aproject");
        Files.createDirectory(projDir);
        String[] initArgs = new String[] {
                "-w", projDir.toString(),
                "init",
                "--cdm-url", cdmBaseUrl,
                "-c", COLLECTION_ID };
        executeExpectSuccess(initArgs);

        MigrationProject project = MigrationProjectFactory.loadMigrationProject(projDir);
        MigrationProjectProperties properties = project.getProjectProperties();
        assertPropertiesSet(properties, "aproject", COLLECTION_ID);

        assertTrue("Description folder not created", Files.exists(project.getDescriptionsPath()));

        assertCdmFieldsPresent(project);
    }

    @Test
    public void initCdmCollectioNotFoundTest() throws Exception {
        String[] initArgs = new String[] {
                "-w", baseDir.toString(),
                "init",
                "--cdm-url", cdmBaseUrl,
                "-p", "unknowncoll" };
        executeExpectFailure(initArgs);

        assertTrue("Collection should not be found on server, but output was " + output,
                output.contains("No collection with ID 'unknowncoll' found on server"));

        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(baseDir)) {
            assertFalse("Project directory should not have been created, so base dir should be empty",
                    dirStream.iterator().hasNext());
        }
    }

    @Test
    public void initCdmProjectAlreadyExistsTest() throws Exception {
        Path projDir = baseDir.resolve("aproject");
        Files.createDirectory(projDir);
        String[] initArgs = new String[] {
                "-w", projDir.toString(),
                "init",
                "--cdm-url", cdmBaseUrl,
                "-c", COLLECTION_ID };
        executeExpectSuccess(initArgs);

        // Run it a second time, should cause a failure
        executeExpectFailure(initArgs);
        assertTrue("Error response should indicate already initialized, but output was: " + output,
                output.contains("the directory already contains a migration project"));

        // The migration project should still be there
        MigrationProject project = MigrationProjectFactory.loadMigrationProject(projDir);
        MigrationProjectProperties properties = project.getProjectProperties();
        assertPropertiesSet(properties, "aproject", COLLECTION_ID);

        assertTrue("Description folder not created", Files.exists(project.getDescriptionsPath()));

        assertCdmFieldsPresent(project);
    }

    private void assertPropertiesSet(MigrationProjectProperties properties, String expName, String expCollId) {
        assertEquals(USERNAME, properties.getCreator());
        assertEquals("Project name did not match expected value", expName, properties.getName());
        assertEquals("CDM Collection ID did not match expected value", expCollId, properties.getCdmCollectionId());
        assertNotNull("Created date not set", properties.getCreatedDate());
    }

    private void assertCdmFieldsPresent(MigrationProject project) throws IOException {
        CdmFieldInfo fieldInfo = fieldService.loadFieldsFromProject(project);
        List<CdmFieldEntry> fields = fieldInfo.getFields();
        assertHasFieldWithValue("title", "title", "Title", false, fields);
        assertHasFieldWithValue("creato", "creato", "Creator", false, fields);
        assertHasFieldWithValue("date", "date", "Creation Date", false, fields);
        assertHasFieldWithValue("data", "data", "Date", false, fields);
        assertHasFieldWithValue("digitb", "digitb", "Digital Collection", false, fields);
    }

    private void assertHasFieldWithValue(String nick, String expectedExport, String expectedDesc,
            boolean expectedSkip, List<CdmFieldEntry> fields) {
        Optional<CdmFieldEntry> matchOpt = fields.stream().filter(f -> nick.equals(f.getNickName())).findFirst();
        assertTrue("Field " + nick + " not present", matchOpt.isPresent());
        CdmFieldEntry entry = matchOpt.get();
        assertEquals(expectedDesc, entry.getDescription());
        assertEquals(expectedExport, entry.getExportAs());
        assertEquals(expectedSkip, entry.getSkipExport());
    }
}
