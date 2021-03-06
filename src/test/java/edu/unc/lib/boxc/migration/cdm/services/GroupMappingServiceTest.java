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

import edu.unc.lib.boxc.migration.cdm.exceptions.InvalidProjectStateException;
import edu.unc.lib.boxc.migration.cdm.exceptions.StateAlreadyExistsException;
import edu.unc.lib.boxc.migration.cdm.model.CdmFieldInfo;
import edu.unc.lib.boxc.migration.cdm.model.GroupMappingInfo;
import edu.unc.lib.boxc.migration.cdm.model.GroupMappingInfo.GroupMapping;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProjectProperties;
import edu.unc.lib.boxc.migration.cdm.options.GroupMappingOptions;
import edu.unc.lib.boxc.migration.cdm.test.CdmEnvironmentHelper;
import edu.unc.lib.boxc.migration.cdm.test.OutputHelper;
import edu.unc.lib.boxc.migration.cdm.test.SipServiceHelper;
import edu.unc.lib.boxc.migration.cdm.util.ProjectPropertiesSerialization;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author bbpennel
 */
public class GroupMappingServiceTest {
    private static final String PROJECT_NAME = "proj";

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    private MigrationProject project;
    private CdmIndexService indexService;
    private CdmFieldService fieldService;
    private GroupMappingService service;
    private SipServiceHelper testHelper;

    @Before
    public void setup() throws Exception {
        project = MigrationProjectFactory.createMigrationProject(
                tmpFolder.getRoot().toPath(), PROJECT_NAME, null, "user", CdmEnvironmentHelper.DEFAULT_ENV_ID);
        Files.createDirectories(project.getExportPath());

        fieldService = new CdmFieldService();
        indexService = new CdmIndexService();
        indexService.setProject(project);
        indexService.setFieldService(fieldService);
        service = new GroupMappingService();
        service.setIndexService(indexService);
        service.setProject(project);
        service.setFieldService(fieldService);

        testHelper = new SipServiceHelper(project, tmpFolder.getRoot().toPath());
    }

    @Test
    public void generateNoIndexTest() throws Exception {
        GroupMappingOptions options = makeDefaultOptions();

        try {
            service.generateMapping(options);
            fail();
        } catch (InvalidProjectStateException e) {
            assertExceptionContains("Project must be indexed", e);
            assertMappedDateNotPresent();
        }
    }

    @Test
    public void generateSingleRunTest() throws Exception {
        indexExportSamples();
        GroupMappingOptions options = makeDefaultOptions();
        service.generateMapping(options);

        GroupMappingInfo info = service.loadMappings();
        assertGroupAMappingsPresent(info);
        assertMappedDatePresent();
    }

    @Test
    public void generateDryRunTest() throws Exception {
        OutputHelper.captureOutput(() -> {
            try {
                indexExportSamples();
                GroupMappingOptions options = makeDefaultOptions();
                options.setDryRun(true);
                service.generateMapping(options);

                service.loadMappings();
                fail();
            } catch (NoSuchFileException e) {
                // expected
            }
        });

        assertMappedDateNotPresent();
    }

    @Test
    public void generateDoubleRunTest() throws Exception {
        indexExportSamples();
        GroupMappingOptions options = makeDefaultOptions();
        service.generateMapping(options);

        try {
            service.generateMapping(options);
            fail();
        } catch (StateAlreadyExistsException e) {
            // expected
        }

        // mapping state should be unchanged
        GroupMappingInfo info = service.loadMappings();
        assertGroupAMappingsPresent(info);

        assertMappedDatePresent();
    }

    @Test
    public void generateSecondDryRunTest() throws Exception {
        OutputHelper.captureOutput(() -> {
            indexExportSamples();
            GroupMappingOptions options = makeDefaultOptions();
            service.generateMapping(options);
            options.setDryRun(true);

            service.generateMapping(options);
            // mapping state should be unchanged
            GroupMappingInfo info = service.loadMappings();
            assertGroupAMappingsPresent(info);
            assertMappedDatePresent();
        });
    }

    @Test
    public void generateForceRunTest() throws Exception {
        indexExportSamples();
        GroupMappingOptions options = makeDefaultOptions();
        service.generateMapping(options);

        GroupMappingInfo info = service.loadMappings();
        assertGroupAMappingsPresent(info);

        options.setForce(true);
        options.setGroupField("digitc");

        service.generateMapping(options);

        // Mapping state should have been overwritten
        GroupMappingInfo info2 = service.loadMappings();
        assertMappingPresent(info2, "25", "digitc:2005-11-10");
        assertMappingPresent(info2, "26", null);
        assertMappingPresent(info2, "27", null);
        assertMappingPresent(info2, "28", "digitc:2005-11-10");
        assertMappingPresent(info2, "29", "digitc:2005-11-10");
        assertEquals(5, info2.getMappings().size());

        assertGroupingPresent(info2, "digitc:2005-11-10", "25", "28", "29");
        assertEquals(1, info2.getGroupedMappings().size());

        assertMappedDatePresent();
    }

    @Test
    public void generateUpdateRunTest() throws Exception {
        indexExportSamples();
        GroupMappingOptions options = makeDefaultOptions();
        service.generateMapping(options);

        GroupMappingInfo info = service.loadMappings();
        assertEquals(5, info.getMappings().size());

        options.setUpdate(true);
        options.setGroupField("digitc");

        service.generateMapping(options);

        // Mapping state should have been overwritten
        GroupMappingInfo info2 = service.loadMappings();
        assertMappingPresent(info2, "25", "groupa:group1");
        assertMappingPresent(info2, "26", "groupa:group1");
        assertMappingPresent(info2, "27", null);
        assertMappingPresent(info2, "28", "digitc:2005-11-10");
        assertMappingPresent(info2, "29", "digitc:2005-11-10");
        assertEquals(5, info2.getMappings().size());

        assertMappedDatePresent();
    }

    @Test
    public void generateUpdateForceRunTest() throws Exception {
        indexExportSamples();
        GroupMappingOptions options = makeDefaultOptions();
        options.setGroupField("digitc");
        service.generateMapping(options);

        GroupMappingInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "digitc:2005-11-10");
        assertMappingPresent(info, "26", null);
        assertMappingPresent(info, "27", null);
        assertMappingPresent(info, "28", "digitc:2005-11-10");
        assertMappingPresent(info, "29", "digitc:2005-11-10");
        assertEquals(5, info.getMappings().size());

        options.setUpdate(true);
        options.setForce(true);
        options.setGroupField("groupa");
        service.generateMapping(options);

        GroupMappingInfo info2 = service.loadMappings();
        assertMappingPresent(info2, "25", "groupa:group1");
        assertMappingPresent(info2, "26", "groupa:group1");
        assertMappingPresent(info2, "27", null);
        assertMappingPresent(info2, "28", "digitc:2005-11-10");
        assertMappingPresent(info2, "29", "digitc:2005-11-10");
        assertEquals(5, info2.getMappings().size());

        assertMappedDatePresent();
    }

    @Test
    public void syncNotIndexedTest() throws Exception {
        try {
            service.syncMappings();
            fail();
        } catch (InvalidProjectStateException e) {
            assertExceptionContains("Project must be indexed", e);
            assertMappedDateNotPresent();
            assertSynchedDateNotPresent();
        }
    }

    @Test
    public void syncNotGeneratedTest() throws Exception {
        indexExportSamples();
        try {
            service.syncMappings();
            fail();
        } catch (InvalidProjectStateException e) {
            assertExceptionContains("Project has not previously generated group mappings", e);
            assertMappedDateNotPresent();
            assertSynchedDateNotPresent();
        }
    }

    @Test
    public void syncSingleRunTest() throws Exception {
        indexExportSamples();
        GroupMappingOptions options = makeDefaultOptions();
        service.generateMapping(options);

        service.syncMappings();

        Connection conn = null;
        try {
            GroupMappingInfo info = service.loadMappings();
            conn = indexService.openDbConnection();
            assertWorkSynched(conn, "groupa:group1", "Redoubt C", "2005-11-23");
            assertFilesGrouped(conn, "groupa:group1", "25", "26");
            // Group key with a single child should not be grouped
            assertNumberOfGroups(conn, 1);
            assertParentIdsPresent(conn, "groupa:group1", null);
            assertSynchedDatePresent();
        } finally {
            CdmIndexService.closeDbConnection(conn);
        }
    }

    @Test
    public void syncSecondRunWithCleanupTest() throws Exception {
        indexExportSamples();
        GroupMappingOptions options = makeDefaultOptions();
        options.setGroupField("digitc");
        service.generateMapping(options);

        service.syncMappings();

        Connection conn = null;
        try {
            GroupMappingInfo info = service.loadMappings();
            conn = indexService.openDbConnection();
            assertWorkSynched(conn, "digitc:2005-11-10", "Redoubt C", "2005-11-23");
            assertFilesGrouped(conn, "digitc:2005-11-10", "25", "28", "29");
            assertNumberOfGroups(conn, 1);
            assertParentIdsPresent(conn, "digitc:2005-11-10", null);
            assertSynchedDatePresent();
        } finally {
            CdmIndexService.closeDbConnection(conn);
        }

        options.setGroupField("groupa");
        options.setForce(true);
        service.generateMapping(options);

        service.syncMappings();

        try {
            GroupMappingInfo info = service.loadMappings();
            conn = indexService.openDbConnection();
            assertWorkSynched(conn, "groupa:group1", "Redoubt C", "2005-11-23");
            assertFilesGrouped(conn, "groupa:group1", "25", "26");
            // Group key with a single child should not be grouped
            assertNumberOfGroups(conn, 1);
            assertParentIdsPresent(conn, "groupa:group1", null);

            assertGroupingPresent(info, "groupa:group1", "25", "26");
            assertEquals(1, info.getGroupedMappings().size());

            assertSynchedDatePresent();
        } finally {
            CdmIndexService.closeDbConnection(conn);
        }
    }

    private void assertWorkSynched(Connection conn, String workId, String expectedTitle, String expectedCreated)
            throws Exception {
        String groupKey = asGroupKey(workId);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select * from " + CdmIndexService.TB_NAME
                + " where " + CdmFieldInfo.CDM_ID + " = '" + groupKey + "'");
        while (rs.next()) {
            String cdmTitle = rs.getString("title");
            String cdmCreated = rs.getString(CdmFieldInfo.CDM_CREATED);
            assertEquals(expectedTitle, cdmTitle);
            assertEquals(expectedCreated, cdmCreated);
            assertEquals(CdmIndexService.ENTRY_TYPE_GROUPED_WORK, rs.getString(CdmIndexService.ENTRY_TYPE_FIELD));
            assertNull(rs.getString(CdmIndexService.PARENT_ID_FIELD));
        }
    }

    private void assertFilesGrouped(Connection conn, String workId, String... expectedFileCdmIds)
            throws Exception {
        String groupKey = asGroupKey(workId);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select " + CdmFieldInfo.CDM_ID
                + " from " + CdmIndexService.TB_NAME
                + " where " + CdmIndexService.PARENT_ID_FIELD + " = '" + groupKey + "'");
        List<String> childIds = new ArrayList<>();
        while (rs.next()) {
            childIds.add(rs.getString(1));
        }
        List<String> expected = Arrays.asList(expectedFileCdmIds);
        assertTrue("Expected work " + workId + " to contain children " + expected
                + " but it contained " + childIds, childIds.containsAll(expected));
        assertEquals(expected.size(), childIds.size());
    }

    private void assertNumberOfGroups(Connection conn, int expected) throws Exception {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select count(distinct " + CdmFieldInfo.CDM_ID + ")"
                + " from " + CdmIndexService.TB_NAME
                + " where " + CdmIndexService.ENTRY_TYPE_FIELD
                    + " = '" + CdmIndexService.ENTRY_TYPE_GROUPED_WORK + "'");
        while (rs.next()) {
            assertEquals(expected, rs.getInt(1));
        }
    }

    private void assertParentIdsPresent(Connection conn, String... expectedParents) throws Exception {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select distinct " + CdmIndexService.PARENT_ID_FIELD
                + " from " + CdmIndexService.TB_NAME);
        List<String> parentIds = new ArrayList<>();
        while (rs.next()) {
            parentIds.add(rs.getString(1));
        }
        var expected = Arrays.stream(expectedParents).map(this::asGroupKey).collect(Collectors.toList());
        assertTrue("Expected parent ids " + expected + " but found " + parentIds, parentIds.containsAll(expected));
        assertEquals("Expected parent ids " + expected + " but found " + parentIds, expected.size(), parentIds.size());
    }

    /**
     * @param matchValue
     * @return the provided match value (fieldname+value) with the group prefix added, which is the expected
     *   format for group keys, or null if the value is null
     */
    private String asGroupKey(String matchValue) {
        if (matchValue == null) {
            return null;
        } else {
            return GroupMappingInfo.GROUPED_WORK_PREFIX + matchValue;
        }
    }

    private GroupMappingOptions makeDefaultOptions() {
        GroupMappingOptions options = new GroupMappingOptions();
        options.setGroupField("groupa");
        return options;
    }

    private void assertExceptionContains(String expected, Exception e) {
        assertTrue("Expected message exception to contain '" + expected + "', but was: " + e.getMessage(),
                e.getMessage().contains(expected));
    }

    private void assertMappingPresent(GroupMappingInfo info, String id, String expectedMatchedVal) {
        GroupMapping mapping = info.getMappingByCdmId(id);
        assertNotNull(mapping);
        assertEquals(id, mapping.getCdmId());
        assertEquals(asGroupKey(expectedMatchedVal), mapping.getGroupKey());
    }

    private void assertGroupingPresent(GroupMappingInfo groupedInfo, String groupKey, String... cdmIds) {
        Map<String, List<String>> groupedMappings = groupedInfo.getGroupedMappings();
        List<String> objIds = groupedMappings.get(asGroupKey(groupKey));
        List<String> expectedIds = Arrays.asList(cdmIds);
        assertTrue("Expected group " + groupKey + " to contain " + expectedIds + " but contained " + objIds,
                objIds.containsAll(expectedIds));
        assertEquals("Expected group " + groupKey + " to contain " + expectedIds + " but contained " + objIds,
                expectedIds.size(), objIds.size());
    }

    private void assertMappedDatePresent() throws Exception {
        MigrationProjectProperties props = ProjectPropertiesSerialization.read(project.getProjectPropertiesPath());
        assertNotNull(props.getGroupMappingsUpdatedDate());
    }

    private void assertMappedDateNotPresent() throws Exception {
        MigrationProjectProperties props = ProjectPropertiesSerialization.read(project.getProjectPropertiesPath());
        assertNull(props.getGroupMappingsUpdatedDate());
    }

    private void assertSynchedDatePresent() throws Exception {
        MigrationProjectProperties props = ProjectPropertiesSerialization.read(project.getProjectPropertiesPath());
        assertNotNull(props.getGroupMappingsSynchedDate());
    }

    private void assertSynchedDateNotPresent() throws Exception {
        MigrationProjectProperties props = ProjectPropertiesSerialization.read(project.getProjectPropertiesPath());
        assertNull(props.getGroupMappingsSynchedDate());
    }

    private void assertGroupAMappingsPresent(GroupMappingInfo info) {
        assertMappingPresent(info, "25", "groupa:group1");
        assertMappingPresent(info, "26", "groupa:group1");
        // Single member group should not be mapped
        assertMappingPresent(info, "27", null);
        assertMappingPresent(info, "28", null);
        assertMappingPresent(info, "29", null);
        assertEquals(5, info.getMappings().size());

        assertGroupingPresent(info, "groupa:group1", "25", "26");
        assertEquals(1, info.getGroupedMappings().size());
    }

    private void indexExportSamples() throws Exception {
        testHelper.indexExportData("grouped_gilmer");
    }
}
