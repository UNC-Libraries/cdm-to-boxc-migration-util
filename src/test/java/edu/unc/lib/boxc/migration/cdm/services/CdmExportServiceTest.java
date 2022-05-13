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

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import edu.unc.lib.boxc.migration.cdm.options.CdmExportOptions;
import edu.unc.lib.boxc.migration.cdm.services.export.ExportStateService;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import edu.unc.lib.boxc.migration.cdm.exceptions.MigrationException;
import edu.unc.lib.boxc.migration.cdm.model.CdmFieldInfo;
import edu.unc.lib.boxc.migration.cdm.model.CdmFieldInfo.CdmFieldEntry;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * @author bbpennel
 */
public class CdmExportServiceTest {
    private static final String PROJECT_NAME = "proj";
    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    private MigrationProject project;
    private CdmFieldService fieldService;
    private CdmExportService service;
    private ExportStateService exportStateService;
    @Mock
    private CdmFileRetrievalService cdmFileRetrievalService;
    private String descAllPath = "src/test/resources/descriptions/gilmer/index/description/desc.all";

    @Before
    public void setup() throws Exception {
        initMocks(this);
        tmpFolder.create();
        project = MigrationProjectFactory.createMigrationProject(
                tmpFolder.getRoot().toPath(), PROJECT_NAME, null, "user");
        fieldService = new CdmFieldService();
        exportStateService = new ExportStateService();
        exportStateService.setProject(project);
        exportStateService.transitionToStarting();
        service = new CdmExportService();
        service.setProject(project);
        service.setCdmFieldService(fieldService);
        service.setExportStateService(exportStateService);
        service.setFileRetrievalService(cdmFileRetrievalService);

        // Trigger population of desc.all file
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                FileUtils.copyFile(new File(descAllPath),
                        project.getExportPath().resolve(CdmFileRetrievalService.DESC_ALL_FILENAME).toFile());
                return null;
            }
        }).when(cdmFileRetrievalService).downloadDescAllFile();
    }

    private CdmExportOptions makeExportOptions() {
        CdmExportOptions options = new CdmExportOptions();
        options.setCdmUsername("user");
        return options;
    }

    @Test
    public void exportAllValidProjectTest() throws Exception {
        CdmFieldInfo fieldInfo = populateFieldInfo();
        fieldService.persistFieldsToProject(project, fieldInfo);

        service.exportAll(makeExportOptions());

        assertTrue("Export folder not created", Files.exists(project.getExportPath()));
        assertExportFileCount(1);
        assertExportedFileContentsMatch(descAllPath);
    }

    @Test
    public void exportAllExportFailureTest() throws Exception {
        doThrow(new MigrationException("Failed to download")).when(cdmFileRetrievalService).downloadDescAllFile();

        CdmFieldInfo fieldInfo = populateFieldInfo();
        fieldService.persistFieldsToProject(project, fieldInfo);

        try {
            service.exportAll(makeExportOptions());
            fail();
        } catch (MigrationException e) {
            // no files sohuld be present
            assertExportFileCount(0);
        }
    }

    private void assertExportFileCount(int expectedCount) throws Exception {
        try (Stream<Path> files = Files.list(project.getExportPath())) {
            long count = files.filter(p -> p.getFileName().toString().startsWith("export")).count();
            assertEquals(expectedCount, count);
        }
    }

    private CdmFieldInfo populateFieldInfo() {
        CdmFieldInfo fieldInfo = new CdmFieldInfo();
        List<CdmFieldEntry> fields = fieldInfo.getFields();
        CdmFieldEntry field1 = new CdmFieldEntry();
        field1.setNickName("title");
        field1.setExportAs("title");
        field1.setDescription("Title");
        fields.add(field1);
        CdmFieldEntry field2 = new CdmFieldEntry();
        field2.setNickName("desc");
        field2.setExportAs("description");
        field2.setDescription("Abstract");
        fields.add(field2);
        return fieldInfo;
    }

    private void assertExportedFileContentsMatch(String expectContentPath) throws Exception {
        assertEquals(IOUtils.toString(getClass().getResourceAsStream(expectContentPath), StandardCharsets.UTF_8),
                FileUtils.readFileToString(CdmFileRetrievalService.getDescAllPath(project).toFile(), StandardCharsets.UTF_8));
    }
}
