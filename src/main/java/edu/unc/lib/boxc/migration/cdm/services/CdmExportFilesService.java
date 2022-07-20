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
import edu.unc.lib.boxc.migration.cdm.exceptions.MigrationException;
import edu.unc.lib.boxc.migration.cdm.model.CdmFieldInfo;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProjectProperties;
import edu.unc.lib.boxc.migration.cdm.model.SourceFilesInfo.SourceFileMapping;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Service for exporting source files from CDM to a chompb project for ingest
 *
 * @author bbpennel
 */
public class CdmExportFilesService {
    private static final Logger log = getLogger(CdmExportFilesService.class);

    private CdmFileRetrievalService fileRetrievalService;
    private MigrationProject project;
    private SourceFileService sourceFileService;
    private CdmIndexService indexService;

    /**
     * Export files from CDM associated with items that do not already have source files mapped
     * @return Result message if any issues were encountered, otherwise null.
     * @throws IOException
     */
    public String exportUnmapped() throws IOException {
        validateProjectState();

        var failedToDownload = new AtomicBoolean();
        var originalPath = sourceFileService.getMappingPath();
        var updatedPath = sourceFileService.getTempMappingPath();
        Connection conn = null;
        // Simultaneously read from the existing mapping and write to a new temporary mapping
        try (
            var originalParser = SourceFileService.openMappingsParser(originalPath);
            var updatedPrinter = SourceFileService.openMappingsPrinter(updatedPath);
        ) {
            conn = indexService.openDbConnection();
            // Have to make reference to connection final so it can be used inside the download block
            final var dbConn = conn;
            var imageDir = fileRetrievalService.getSshCollectionPath().resolve(CdmFileRetrievalService.IMAGE_SUBPATH);

            fileRetrievalService.executeDownloadBlock((scpClient -> {
                try {
                    var exportSourceFilesPath = initializeExportSourceFilesDir();

                    for (CSVRecord originalRecord : originalParser) {
                        var origMapping = SourceFileService.recordToMapping(originalRecord);
                        // skip over already populated mappings
                        if (origMapping.getSourcePath() != null) {
                            SourceFileService.writeMapping(updatedPrinter, origMapping);
                            continue;
                        }

                        // Figure out name of associated file and download it
                        var filename = retrieveSourceFileName(dbConn, origMapping);
                        var filePath = imageDir.resolve(filename).toString();
                        var destPath = exportSourceFilesPath.resolve(filename);
                        try {
                            scpClient.download(filePath, destPath);
                        }  catch (IOException e) {
                            log.warn("Failed to download file {} to {}", filePath, destPath, e);
                            failedToDownload.set(true);
                            continue;
                        }
                        // Update mapping to include downloaded file
                        origMapping.setSourcePath(destPath.toString());
                        origMapping.setMatchingValue(filename);
                        SourceFileService.writeMapping(updatedPrinter, origMapping);
                    }
                } catch (IOException | SQLException e) {
                    throw new MigrationException("Encountered an error while downloading source files", e);
                }
            }));
        } catch (SQLException e) {
            throw new MigrationException("Failed to establish database connection", e);
        } finally {
            CdmIndexService.closeDbConnection(conn);
        }
        // Switch the updated mapping file over to being the primary mapping
        var swapPath = Paths.get(originalPath.toString() + "_old");
        Files.move(originalPath, swapPath);
        Files.move(updatedPath, originalPath);
        Files.delete(swapPath);

        return failedToDownload.get() ? "One or more source files failed to download, check the logs" : null;
    }

    private static final String FILENAME_QUERY =
            "select find from " + CdmIndexService.TB_NAME + " where " + CdmFieldInfo.CDM_ID + " = ?";

    private String retrieveSourceFileName(Connection conn, SourceFileMapping mapping) throws SQLException {
        try (var filenameStmt = conn.prepareStatement(FILENAME_QUERY)) {
            filenameStmt.setString(1, mapping.getCdmId());
            var resultSet = filenameStmt.executeQuery();
            if (resultSet.next()) {
                return resultSet.getString(1);
            } else {
                throw new MigrationException("No record found in index for mapped id " + mapping.getCdmId());
            }
        }
    }

    private Path initializeExportSourceFilesDir() throws IOException {
        var path = CdmFileRetrievalService.getExportedSourceFilesPath(project);
        Files.createDirectories(path);
        return path;
    }

    private void validateProjectState() {
        MigrationProjectProperties props = project.getProjectProperties();
        if (props.getIndexedDate() == null) {
            throw new InvalidProjectStateException("Exported data must be indexed");
        }
        if (props.getSourceFilesUpdatedDate() == null) {
            throw new InvalidProjectStateException("Source files must be mapped");
        }
    }

    public void setFileRetrievalService(CdmFileRetrievalService fileRetrievalService) {
        this.fileRetrievalService = fileRetrievalService;
    }

    public void setProject(MigrationProject project) {
        this.project = project;
    }

    public void setSourceFileService(SourceFileService sourceFileService) {
        this.sourceFileService = sourceFileService;
    }

    public void setIndexService(CdmIndexService indexService) {
        this.indexService = indexService;
    }
}
