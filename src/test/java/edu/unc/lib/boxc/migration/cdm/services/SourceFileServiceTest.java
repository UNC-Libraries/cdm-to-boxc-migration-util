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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import edu.unc.lib.boxc.migration.cdm.test.CdmEnvironmentHelper;
import edu.unc.lib.boxc.migration.cdm.test.OutputHelper;
import edu.unc.lib.boxc.migration.cdm.test.SipServiceHelper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import edu.unc.lib.boxc.migration.cdm.exceptions.InvalidProjectStateException;
import edu.unc.lib.boxc.migration.cdm.exceptions.MigrationException;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProjectProperties;
import edu.unc.lib.boxc.migration.cdm.model.SourceFilesInfo;
import edu.unc.lib.boxc.migration.cdm.model.SourceFilesInfo.SourceFileMapping;
import edu.unc.lib.boxc.migration.cdm.options.SourceFileMappingOptions;
import edu.unc.lib.boxc.migration.cdm.util.ProjectPropertiesSerialization;

/**
 * @author bbpennel
 */
public class SourceFileServiceTest {
    private static final String PROJECT_NAME = "proj";

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    private MigrationProject project;
    private CdmIndexService indexService;
    private CdmFieldService fieldService;
    private SourceFileService service;
    private SipServiceHelper testHelper;

    private Path basePath;

    @Before
    public void setup() throws Exception {
        project = MigrationProjectFactory.createMigrationProject(
                tmpFolder.getRoot().toPath(), PROJECT_NAME, null, "user", CdmEnvironmentHelper.DEFAULT_ENV_ID);
        Files.createDirectories(project.getExportPath());

        basePath = tmpFolder.newFolder().toPath();
        testHelper = new SipServiceHelper(project, basePath);

        service = testHelper.getSourceFileService();
    }

    @Test
    public void generateNoIndexTest() throws Exception {
        SourceFileMappingOptions options = makeDefaultOptions();

        try {
            service.generateMapping(options);
            fail();
        } catch (InvalidProjectStateException e) {
            assertExceptionContains("Project must be indexed", e);
            assertMappedDateNotPresent();
        }
    }

    @Test
    public void generateBasePathIsNotADirectoryTest() throws Exception {
        setIndexedDate();
        SourceFileMappingOptions options = makeDefaultOptions();
        Files.delete(basePath);
        Files.createFile(basePath);

        try {
            service.generateMapping(options);
            fail();
        } catch (IllegalArgumentException e) {
            assertExceptionContains("Base path must be a directory", e);
            assertMappedDateNotPresent();
        }
    }

    @Test
    public void generateBasePathDoesNotExistTest() throws Exception {
        setIndexedDate();
        SourceFileMappingOptions options = makeDefaultOptions();
        Files.delete(basePath);

        try {
            service.generateMapping(options);
            fail();
        } catch (IllegalArgumentException e) {
            assertExceptionContains("Base path must be a directory", e);
            assertMappedDateNotPresent();
        }
    }

    @Test
    public void generateNoSourceFilesTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", null);
        assertMappingPresent(info, "26", "276_183_E.tif", null);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        assertMappedDatePresent();
    }

    @Test
    public void generateExactMatchTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");

        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info, "26", "276_183_E.tif", null);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        assertMappedDatePresent();
    }

    @Test
    public void generateTransformedMatchTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setFieldMatchingPattern("(\\d+)\\_([^_]+)\\_E.tif");
        options.setFilenameTemplate("00$1_op0$2_0001_e.tif");

        Path srcPath1 = testHelper.addSourceFile("00276_op0182_0001_e.tif");
        Path srcPath3 = testHelper.addSourceFile("00276_op0203_0001_e.tif");

        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info, "26", "276_183_E.tif", null);
        assertMappingPresent(info, "27", "276_203_E.tif", srcPath3);

        assertMappedDatePresent();
    }

    @Test
    public void generateNestedMatchesTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setPathPattern("**/*.tif");
        Path srcPath1 = testHelper.addSourceFile("nested/path/276_182_E.tif");
        Path srcPath2 = testHelper.addSourceFile("276_183_E.tif");

        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info, "26", "276_183_E.tif", srcPath2);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        assertMappedDatePresent();
    }

    @Test
    public void generateTransformedNestedMatchTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setFieldMatchingPattern("(\\d+)\\_([^_]+)\\_E.tif");
        options.setFilenameTemplate("00$1_op0$2_0001_e.tif");
        Path srcPath1 = testHelper.addSourceFile("nested/path/00276_op0182_0001_e.tif");
        // Add extra file that does not match the pattern
        testHelper.addSourceFile("nested/path/276_182_E.tif");

        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info, "26", "276_183_E.tif", null);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        assertMappedDatePresent();
    }

    @Test
    public void generateMultipleMatchesForSameObjectTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setPathPattern("**/*.tif");
        Path srcPath1 = testHelper.addSourceFile("nested/path/276_182_E.tif");
        Path srcPath1Dupe = testHelper.addSourceFile("nested/276_182_E.tif");
        Path srcPath2 = testHelper.addSourceFile("276_183_E.tif");

        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", null, srcPath1, srcPath1Dupe);
        assertMappingPresent(info, "26", "276_183_E.tif", srcPath2);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        assertMappedDatePresent();
    }

    @Test
    public void generateDryRunTest() throws Exception {
        OutputHelper.captureOutput(() -> {
            testHelper.indexExportData("mini_gilmer");
            SourceFileMappingOptions options = makeDefaultOptions();
            options.setDryRun(true);
            testHelper.addSourceFile("276_182_E.tif");

            service.generateMapping(options);

            assertFalse(Files.exists(project.getSourceFilesMappingPath()));

            assertMappedDateNotPresent();
        });
    }

    @Test
    public void generateMappingExistsTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");

        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info, "26", "276_183_E.tif", null);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        // Add extra matching source file, which should not show up during rejected run
        Path srcPath2 = testHelper.addSourceFile("276_183_E.tif");
        try {
            service.generateMapping(options);
            fail();
        } catch (MigrationException e) {
            assertTrue(e.getMessage().contains("Cannot create mapping, a file already exists"));
        }

        SourceFilesInfo info2 = service.loadMappings();
        assertMappingPresent(info2, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info2, "26", "276_183_E.tif", null);
        assertMappingPresent(info2, "27", "276_203_E.tif", null);

        // Try again with force
        options.setForce(true);
        service.generateMapping(options);

        SourceFilesInfo info3 = service.loadMappings();
        assertMappingPresent(info3, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info3, "26", "276_183_E.tif", srcPath2);
        assertMappingPresent(info3, "27", "276_203_E.tif", null);

        assertMappedDatePresent();
    }

    @Test
    public void generateMatchesLowercaseTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setLowercaseTemplate(true);
        Path srcPath1 = testHelper.addSourceFile("276_182_e.tif");
        Path srcPath2 = testHelper.addSourceFile("276_183_E.tif");

        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info, "26", "276_183_E.tif", srcPath2);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        assertMappedDatePresent();
    }

    @Test
    public void generateUpdateNoExistingFileTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setUpdate(true);
        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");

        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info, "26", "276_183_E.tif", null);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        assertMappedDatePresent();
    }

    @Test
    public void generateUpdateExistingNoChangesTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");

        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info, "26", "276_183_E.tif", null);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        assertMappedDatePresent();

        options.setUpdate(true);
        service.generateMapping(options);

        SourceFilesInfo info2 = service.loadMappings();
        assertMappingPresent(info2, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info2, "26", "276_183_E.tif", null);
        assertMappingPresent(info2, "27", "276_203_E.tif", null);
    }

    @Test
    public void generateUpdateAddNewSourceFileTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");

        service.generateMapping(options);

        Path srcPath2 = testHelper.addSourceFile("276_183_E.tif");
        options.setUpdate(true);
        service.generateMapping(options);

        SourceFilesInfo info2 = service.loadMappings();
        assertMappingPresent(info2, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info2, "26", "276_183_E.tif", srcPath2);
        assertMappingPresent(info2, "27", "276_203_E.tif", null);
    }

    @Test
    public void generateUpdateChangeSourceFileTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setPathPattern("**/*");
        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");

        service.generateMapping(options);

        Files.delete(srcPath1);
        testHelper.addSourceFile("nested/276_182_E.tif");
        options.setUpdate(true);
        service.generateMapping(options);

        SourceFilesInfo info2 = service.loadMappings();
        // Should still list the original source path
        assertMappingPresent(info2, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info2, "26", "276_183_E.tif", null);
        assertMappingPresent(info2, "27", "276_203_E.tif", null);
    }

    @Test
    public void generateUpdateChangeSourceFileWithForceTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setPathPattern("**/*");
        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");

        service.generateMapping(options);

        Files.delete(srcPath1);
        Path srcPath2 = testHelper.addSourceFile("nested/276_182_E.tif");
        options.setUpdate(true);
        options.setForce(true);
        service.generateMapping(options);

        SourceFilesInfo info2 = service.loadMappings();
        // Should be using the new path
        assertMappingPresent(info2, "25", "276_182_E.tif", srcPath2);
        assertMappingPresent(info2, "26", "276_183_E.tif", null);
        assertMappingPresent(info2, "27", "276_203_E.tif", null);
    }

    @Test
    public void generateUpdateAddPotentialMatchToExistingMatchTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setPathPattern("**/*");
        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");

        service.generateMapping(options);

        testHelper.addSourceFile("nested/276_182_E.tif");
        options.setUpdate(true);
        service.generateMapping(options);

        SourceFilesInfo info2 = service.loadMappings();
        // Should be using the new path
        assertMappingPresent(info2, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info2, "26", "276_183_E.tif", null);
        assertMappingPresent(info2, "27", "276_203_E.tif", null);
    }

    @Test
    public void generateUpdateAddPotentialMatchTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setPathPattern("**/*");

        service.generateMapping(options);
        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", null);
        assertMappingPresent(info, "26", "276_183_E.tif", null);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");
        Path srcPath2 = testHelper.addSourceFile("nested/276_182_E.tif");
        options.setUpdate(true);
        service.generateMapping(options);

        SourceFilesInfo info2 = service.loadMappings();
        // Should be using the new path
        assertMappingPresent(info2, "25", "276_182_E.tif", null, srcPath1, srcPath2);
        assertMappingPresent(info2, "26", "276_183_E.tif", null);
        assertMappingPresent(info2, "27", "276_203_E.tif", null);
    }

    @Test
    public void generateUpdateAddPotentialMatchWithExistingPotentialTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setPathPattern("**/*");

        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");
        Path srcPath2 = testHelper.addSourceFile("nested/276_182_E.tif");

        service.generateMapping(options);
        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "25", "276_182_E.tif", null, srcPath1, srcPath2);
        assertMappingPresent(info, "26", "276_183_E.tif", null);
        assertMappingPresent(info, "27", "276_203_E.tif", null);

        Path srcPath3 = testHelper.addSourceFile("another/276_182_E.tif");
        options.setUpdate(true);
        service.generateMapping(options);

        SourceFilesInfo info2 = service.loadMappings();
        // Should be using the new path
        assertMappingPresent(info2, "25", "276_182_E.tif", null, srcPath1, srcPath2, srcPath3);
        assertMappingPresent(info2, "26", "276_183_E.tif", null);
        assertMappingPresent(info2, "27", "276_203_E.tif", null);
    }

    @Test
    public void generateUpdateAddNewRecordsTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");

        service.generateMapping(options);

        // Add more exported objects
        testHelper.indexExportData("gilmer");

        Path srcPath2 = testHelper.addSourceFile("276_245a_E.tif");
        options.setUpdate(true);
        service.generateMapping(options);

        SourceFilesInfo info2 = service.loadMappings();
        assertMappingPresent(info2, "25", "276_182_E.tif", srcPath1);
        assertMappingPresent(info2, "26", "276_183_E.tif", null);
        assertMappingPresent(info2, "27", "276_203_E.tif", null);
        assertMappingPresent(info2, "28", "276_241_E.tif", null);
        assertMappingPresent(info2, "29", "276_245a_E.tif", srcPath2);
    }

    @Test
    public void generateUpdateDryRunAddNewSourceFileTest() throws Exception {
        testHelper.indexExportData("mini_gilmer");
        SourceFileMappingOptions options = makeDefaultOptions();
        Path srcPath1 = testHelper.addSourceFile("276_182_E.tif");

        service.generateMapping(options);

        OutputHelper.captureOutput(() -> {
            testHelper.addSourceFile("276_183_E.tif");
            options.setUpdate(true);
            options.setDryRun(true);
            service.generateMapping(options);

            SourceFilesInfo info2 = service.loadMappings();
            assertMappingPresent(info2, "25", "276_182_E.tif", srcPath1);
            // Mapping should be unchanged
            assertMappingPresent(info2, "26", "276_183_E.tif", null);
            assertMappingPresent(info2, "27", "276_203_E.tif", null);
        });
    }

    @Test
    public void generateCompoundExactMatchTest() throws Exception {
        testHelper.indexExportData(Paths.get("src/test/resources/keepsakes_fields.csv"), "mini_keepsakes");
        SourceFileMappingOptions options = makeDefaultOptions();
        options.setExportField("filena");
        Path srcPath1 = testHelper.addSourceFile("nccg_ck_09.tif");
        Path srcPath2 = testHelper.addSourceFile("nccg_ck_1042-22_v1.tif");
        Path srcPath3 = testHelper.addSourceFile("nccg_ck_1042-22_v2.tif");
        Path srcPath4 = testHelper.addSourceFile("nccg_ck_549-4_v1.tif");
        Path srcPath5 = testHelper.addSourceFile("nccg_ck_549-4_v2.tif");

        service.generateMapping(options);

        SourceFilesInfo info = service.loadMappings();
        assertMappingPresent(info, "216", "nccg_ck_09.tif", srcPath1);
        assertMappingPresent(info, "602", "nccg_ck_1042-22_v1.tif", srcPath2);
        assertMappingPresent(info, "603", "nccg_ck_1042-22_v2.tif", srcPath3);
        assertMappingPresent(info, "605", "nccg_ck_549-4_v1.tif", srcPath4);
        assertMappingPresent(info, "606", "nccg_ck_549-4_v2.tif", srcPath5);
        // There must not be mapping entries for the compound objects, only for the children
        assertEquals(5, info.getMappings().size());

        assertMappedDatePresent();
    }

    private void assertMappingPresent(SourceFilesInfo info, String cdmid, String matchingVal, Path sourcePath,
            Path... potentialPaths) {
        List<SourceFileMapping> mappings = info.getMappings();
        SourceFileMapping mapping = mappings.stream().filter(m -> m.getCdmId().equals(cdmid)).findFirst().get();

        assertEquals(sourcePath, mapping.getSourcePath());
        assertEquals(matchingVal, mapping.getMatchingValue());
        if (potentialPaths.length > 0) {
            for (Path potentialPath : potentialPaths) {
                assertTrue("Mapping did not contain expected potential path: " + potentialPath,
                        mapping.getPotentialMatches().contains(potentialPath.toString()));
            }
        }
    }

    private SourceFileMappingOptions makeDefaultOptions() {
        SourceFileMappingOptions options = new SourceFileMappingOptions();
        options.setBasePath(basePath);
        options.setExportField("file");
        options.setFieldMatchingPattern("(.+)");
        options.setFilenameTemplate("$1");
        return options;
    }

    private void assertExceptionContains(String expected, Exception e) {
        assertTrue("Expected message exception to contain '" + expected + "', but was: " + e.getMessage(),
                e.getMessage().contains(expected));
    }

    private void setIndexedDate() throws Exception {
        project.getProjectProperties().setIndexedDate(Instant.now());
        ProjectPropertiesSerialization.write(project);
    }

    private void assertMappedDatePresent() throws Exception {
        MigrationProjectProperties props = ProjectPropertiesSerialization.read(project.getProjectPropertiesPath());
        assertNotNull(props.getSourceFilesUpdatedDate());
    }

    private void assertMappedDateNotPresent() throws Exception {
        MigrationProjectProperties props = ProjectPropertiesSerialization.read(project.getProjectPropertiesPath());
        assertNull(props.getSourceFilesUpdatedDate());
    }
}
