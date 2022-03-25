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

import static edu.unc.lib.boxc.migration.cdm.util.CLIConstants.outputLogger;
import static org.slf4j.LoggerFactory.getLogger;

import edu.unc.lib.boxc.migration.cdm.exceptions.InvalidProjectStateException;
import edu.unc.lib.boxc.migration.cdm.exceptions.MigrationException;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.services.CdmFieldService;
import edu.unc.lib.boxc.migration.cdm.services.FieldAssessmentTemplateService;
import edu.unc.lib.boxc.migration.cdm.services.MigrationProjectFactory;
import org.slf4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

/**
 * @author bbpennel
 */
@Command(name = "fields",
        description = "Interactions with CDM fields for the current project")
public class CdmFieldsCommand {
    private static final Logger log = getLogger(CdmFieldsCommand.class);
    @ParentCommand
    private CLIMain parentCommand;

    private CdmFieldService fieldService = new CdmFieldService();
    private FieldAssessmentTemplateService templateService = new FieldAssessmentTemplateService();

    @Command(name = "validate",
            description = "Validate the cdm_fields.json file for this project")
    public int validateFields() throws Exception {
        try {
            MigrationProject project = MigrationProjectFactory
                    .loadMigrationProject(parentCommand.getWorkingDirectory());
            fieldService.validateFieldsFile(project);
            outputLogger.info("PASS: CDM fields file at path {} is valid", project.getFieldsPath());
            return 0;
        } catch (InvalidProjectStateException e) {
            outputLogger.info("FAIL: {}", e.getMessage());
            return 1;
        } catch (MigrationException e) {
            log.error("Failed to validate fields file", e);
            outputLogger.info("FAIL: Failed to validate fields file: {}", e.getMessage());
            return 1;
        }
    }

    @Command(name = "generate_report",
            description = "Generate the field assessment spreadsheet template for this project.")
    public int generate() throws Exception {
        try {
            MigrationProject project = MigrationProjectFactory
                    .loadMigrationProject(parentCommand.getWorkingDirectory());
            templateService.setCdmFieldService(fieldService);
            templateService.generate(project);
            outputLogger.info("Report generated");
            return 0;
        } catch (MigrationException | IllegalArgumentException e) {
            outputLogger.info("{}", e.getMessage());
            return 1;
        } catch (Exception e) {
            log.error("Failed to generate report", e);
            outputLogger.info("Failed to generate report: {}", e.getMessage());
            return 1;
        }
    }
}
