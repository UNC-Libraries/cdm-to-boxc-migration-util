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

import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import edu.unc.lib.boxc.migration.cdm.model.CdmFieldInfo;
import edu.unc.lib.boxc.migration.cdm.model.CdmFieldInfo.CdmFieldEntry;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.services.CdmFieldService;
import edu.unc.lib.boxc.migration.cdm.services.MigrationProjectFactory;

/**
 * @author bbpennel
 */
public class CdmFieldsCommandIT extends AbstractCommandIT {

    private CdmFieldService fieldService;

    @Before
    public void setUp() throws Exception {
        fieldService = new CdmFieldService();
    }

    @After
    public void cleanup() {
        System.setOut(originalOut);
    }

    @Test
    public void validateValidFieldsConfigTest() throws Exception {
        Path projPath = baseDir.resolve("proj");
        MigrationProject project = MigrationProjectFactory.createMigrationProject(projPath, null, null, USERNAME);
        CdmFieldInfo fieldInfo = new CdmFieldInfo();
        CdmFieldEntry fieldEntry = new CdmFieldEntry();
        fieldEntry.setNickName("title");
        fieldEntry.setExportAs("titla");
        fieldEntry.setDescription("Title");
        fieldInfo.getFields().add(fieldEntry);
        fieldService.persistFieldsToProject(project, fieldInfo);

        String[] cmdArgs = new String[] {
                "-w", projPath.toString(),
                "fields", "validate"};
        executeExpectSuccess(cmdArgs);
    }

    @Test
    public void validateInvalidFieldsConfigTest() throws Exception {
        Path projPath = baseDir.resolve("proj");
        MigrationProject project = MigrationProjectFactory.createMigrationProject(projPath, null, null, USERNAME);
        CdmFieldInfo fieldInfo = new CdmFieldInfo();
        CdmFieldEntry fieldEntry = new CdmFieldEntry();
        fieldEntry.setNickName("title");
        fieldEntry.setExportAs("titla");
        fieldEntry.setDescription("Title");
        fieldInfo.getFields().add(fieldEntry);
        CdmFieldEntry fieldEntry2 = new CdmFieldEntry();
        fieldEntry2.setNickName("title2");
        fieldEntry2.setExportAs("titla");
        fieldEntry2.setDescription("Another Title");
        fieldInfo.getFields().add(fieldEntry2);
        fieldService.persistFieldsToProject(project, fieldInfo);

        String[] cmdArgs = new String[] {
                "-w", projPath.toString(),
                "fields", "validate"};
        executeExpectFailure(cmdArgs);

        assertOutputContains("Duplicate export_as value 'titla'");
    }
}
