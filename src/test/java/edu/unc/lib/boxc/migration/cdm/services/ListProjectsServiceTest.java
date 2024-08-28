package edu.unc.lib.boxc.migration.cdm.services;

import com.fasterxml.jackson.databind.JsonNode;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.test.BxcEnvironmentHelper;
import edu.unc.lib.boxc.migration.cdm.test.CdmEnvironmentHelper;
import edu.unc.lib.boxc.migration.cdm.test.SipServiceHelper;
import edu.unc.lib.boxc.migration.cdm.util.ProjectPropertiesSerialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.MockitoAnnotations.openMocks;

public class ListProjectsServiceTest {
    private static final String PROJECT_NAME = "proj";
    private static final String PROJECT_NAME_2 = "proj2";

    @TempDir
    public Path tmpFolder;

    private SipServiceHelper testHelper;
    private MigrationProject project;
    private ListProjectsService service;
    private CdmFieldService fieldService;
    private CdmIndexService indexService;
    private ProjectPropertiesService projectPropertiesService;
    private SourceFileService sourceFileService;

    private AutoCloseable closeable;

    @BeforeEach
    public void setup() throws Exception {
        closeable = openMocks(this);
        project = MigrationProjectFactory.createMigrationProject(
                tmpFolder, PROJECT_NAME, null, "user", CdmEnvironmentHelper.DEFAULT_ENV_ID,
                BxcEnvironmentHelper.DEFAULT_ENV_ID, MigrationProject.PROJECT_SOURCE_CDM);
        testHelper = new SipServiceHelper(project, tmpFolder);

        fieldService = new CdmFieldService();
        indexService = new CdmIndexService();
        indexService.setFieldService(fieldService);
        sourceFileService = new SourceFileService();
        sourceFileService.setIndexService(indexService);
        projectPropertiesService = new ProjectPropertiesService();
        service = new ListProjectsService();
        service.setFieldService(fieldService);
        service.setIndexService(indexService);
        service.setPropertiesService(projectPropertiesService);
        service.setSourceFileService(sourceFileService);
    }

    @AfterEach
    void closeService() throws Exception {
        closeable.close();
    }

    @Test
    public void invalidDirectoryTest() throws Exception {
        try {
            service.listProjects(Path.of("test"));
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Path test does not exist"));
        }
    }

    @Test
    public void listProjectsInitializedTest() throws Exception {
        JsonNode list = service.listProjects(tmpFolder);

        assertEquals(tmpFolder.toString(), list.findValue(ListProjectsService.PROJECT_PATH).asText());
        assertEquals("initialized", list.findValue(ListProjectsService.STATUS).asText());
        assertEquals("null", list.findValue(ListProjectsService.ALLOWED_ACTIONS).asText());
        assertEquals(PROJECT_NAME, list.findValue("name").asText());
    }

    @Test
    public void listProjectsIndexedTest() throws Exception {
        project.getProjectProperties().setIndexedDate(Instant.now());
        ProjectPropertiesSerialization.write(project);
        JsonNode list = service.listProjects(tmpFolder);

        assertEquals(tmpFolder.toString(), list.findValue(ListProjectsService.PROJECT_PATH).asText());
        assertEquals("indexed", list.findValue(ListProjectsService.STATUS).asText());
        assertEquals("null", list.findValue(ListProjectsService.ALLOWED_ACTIONS).asText());
        assertEquals(PROJECT_NAME, list.findValue("name").asText());
    }

    @Test
    public void listProjectsSourcesMappedTest() throws Exception {
        project.getProjectProperties().setSourceFilesUpdatedDate(Instant.now());
        ProjectPropertiesSerialization.write(project);
        JsonNode list = service.listProjects(tmpFolder);

        assertEquals(tmpFolder.toString(), list.findValue(ListProjectsService.PROJECT_PATH).asText());
        assertEquals("sources_mapped", list.findValue(ListProjectsService.STATUS).asText());
        assertEquals("null", list.findValue(ListProjectsService.ALLOWED_ACTIONS).asText());
        assertEquals(PROJECT_NAME, list.findValue("name").asText());
    }

    @Test
    public void listProjectsSipsGeneratedTest() throws Exception {
        project.getProjectProperties().setSourceFilesUpdatedDate(Instant.now());
        project.getProjectProperties().setSipsGeneratedDate(Instant.now());
        ProjectPropertiesSerialization.write(project);
        JsonNode list = service.listProjects(tmpFolder);

        assertEquals(tmpFolder.toString(), list.findValue(ListProjectsService.PROJECT_PATH).asText());
        assertEquals("sips_generated", list.findValue(ListProjectsService.STATUS).asText());
        assertEquals("null", list.findValue(ListProjectsService.ALLOWED_ACTIONS).asText());
        assertEquals(PROJECT_NAME, list.findValue("name").asText());
    }

    @Test
    public void listProjectsSipsSubmittedTest() throws Exception {
        project.getProjectProperties().setSipsGeneratedDate(Instant.now());
        project.getProjectProperties().setSipsSubmitted(Collections.singleton("test"));
        ProjectPropertiesSerialization.write(project);
        JsonNode list = service.listProjects(tmpFolder);

        assertEquals(tmpFolder.toString(), list.findValue(ListProjectsService.PROJECT_PATH).asText());
        assertEquals("ingested", list.findValue(ListProjectsService.STATUS).asText());
        assertEquals("null", list.findValue(ListProjectsService.ALLOWED_ACTIONS).asText());
        assertEquals(PROJECT_NAME, list.findValue("name").asText());
    }

    @Test
    public void listProjectsMultipleProjectsTest() throws Exception {
        // project one ingested
        project.getProjectProperties().setSipsGeneratedDate(Instant.now());
        project.getProjectProperties().setSipsSubmitted(Collections.singleton("test"));
        ProjectPropertiesSerialization.write(project);

        // project two initialized
        project = MigrationProjectFactory.createMigrationProject(
                tmpFolder, PROJECT_NAME_2, null, "user", CdmEnvironmentHelper.DEFAULT_ENV_ID,
                BxcEnvironmentHelper.DEFAULT_ENV_ID, MigrationProject.PROJECT_SOURCE_CDM);

        JsonNode list = service.listProjects(tmpFolder);

        assertEquals(tmpFolder.toString(), list.findValue(ListProjectsService.PROJECT_PATH).asText());
        assertTrue(list.findValues(ListProjectsService.STATUS).toString().contains("initialized"));
        assertTrue(list.findValues(ListProjectsService.STATUS).toString().contains("ingested"));
        assertEquals("null", list.findValue(ListProjectsService.ALLOWED_ACTIONS).asText());
        assertTrue(list.findValues("name").toString().contains(PROJECT_NAME_2));
        assertTrue(list.findValues("name").toString().contains(PROJECT_NAME));
    }
}
