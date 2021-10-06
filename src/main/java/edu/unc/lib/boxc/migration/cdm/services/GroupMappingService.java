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

import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

import edu.unc.lib.boxc.migration.cdm.exceptions.InvalidProjectStateException;
import edu.unc.lib.boxc.migration.cdm.exceptions.MigrationException;
import edu.unc.lib.boxc.migration.cdm.exceptions.StateAlreadyExistsException;
import edu.unc.lib.boxc.migration.cdm.model.GroupMappingInfo;
import edu.unc.lib.boxc.migration.cdm.model.GroupMappingInfo.GroupMapping;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.model.SourceFilesInfo;
import edu.unc.lib.boxc.migration.cdm.options.GroupMappingOptions;
import edu.unc.lib.boxc.migration.cdm.util.ProjectPropertiesSerialization;

/**
 * Service for generating and retrieving mappings of objects to works for grouping purposes
 *
 * @author bbpennel
 */
public class GroupMappingService {
    private static final Logger log = getLogger(GroupMappingService.class);

    private static final int FETCH_SIZE = 1000;

    private MigrationProject project;
    private CdmIndexService indexService;

    public void generateMapping(GroupMappingOptions options) throws IOException {
        assertProjectStateValid();
        ensureMappingState(options);

        Path mappingPath = project.getGroupMappingPath();
        boolean needsMerge = false;
        if (options.getUpdate() && Files.exists(mappingPath)) {
            mappingPath = mappingPath.getParent().resolve("~" + mappingPath.getFileName().toString() + "_new");
            // Cleanup temp path if it already exists
            Files.deleteIfExists(mappingPath);
            needsMerge = true;
        }

        // Iterate through exported objects in this collection to match against
        Connection conn = null;
        try (
            // Write to system.out if doing a dry run, otherwise write to mappings file
            BufferedWriter writer = (options.getDryRun() && !needsMerge) ?
                    new BufferedWriter(new OutputStreamWriter(System.out)) :
                    Files.newBufferedWriter(mappingPath);
            CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader(GroupMappingInfo.CSV_HEADERS));
        ) {
            // Query for all values of the export field to be used for grouping
            conn = indexService.openDbConnection();
            Statement stmt = conn.createStatement();
            stmt.setFetchSize(FETCH_SIZE);
            ResultSet rs = stmt.executeQuery("select cdmid, " + options.getGroupField()
                + " from " + CdmIndexService.TB_NAME + " order by cdmid ASC");
            while (rs.next()) {
                String cdmId = rs.getString(1);
                String groupKey = rs.getString(2);

                if (StringUtils.isBlank(groupKey)) {
                    log.debug("No matching field for object {}", cdmId);
                    csvPrinter.printRecord(cdmId, null);
                    continue;
                }
                csvPrinter.printRecord(cdmId, options.getGroupField() + ":" + groupKey);
            }
        } catch (SQLException e) {
            throw new MigrationException("Error interacting with export index", e);
        } finally {
            CdmIndexService.closeDbConnection(conn);
        }

        // Performing update operation with existing mapping, need to merge values
        if (needsMerge) {
            mergeUpdates(options, mappingPath);
        }

        if (!options.getDryRun()) {
            setUpdatedDate(Instant.now());
        }
    }

    private void assertProjectStateValid() {
        if (project.getProjectProperties().getIndexedDate() == null) {
            throw new InvalidProjectStateException("Project must be indexed prior to generating source mappings");
        }
    }

    private void ensureMappingState(GroupMappingOptions options) {
        if (options.getDryRun() || options.getUpdate()) {
            return;
        }
        Path mappingPath = project.getGroupMappingPath();
        if (Files.exists(mappingPath)) {
            if (options.getForce()) {
                try {
                    try {
                        Files.delete(mappingPath);
                    } catch (NoSuchFileException e) {
                        log.debug("File does not exist, skipping deletion");
                    }
                    // Clear date property in case it was set
                    setUpdatedDate(null);
                } catch (IOException e) {
                    throw new MigrationException("Failed to overwrite mapping file", e);
                }
            } else {
                throw new StateAlreadyExistsException("Cannot create mapping, a file already exists."
                        + " Use the force flag to overwrite.");
            }
        }
    }

    /**
     * Merge existing mappings with updated mappings, writing to temporary files as intermediates
     * @param options
     * @param updatesPath
     */
    private void mergeUpdates(GroupMappingOptions options, Path updatesPath) throws IOException {
        Path originalPath = project.getGroupMappingPath();
        Path mergedPath = originalPath.getParent().resolve("~" + originalPath.getFileName().toString() + "_merged");
        // Cleanup temp merged path if it already exists
        Files.deleteIfExists(mergedPath);

        // Load the new mappings into memory
        GroupMappingInfo updateInfo = loadMappings(updatesPath);

        // Iterate through the existing mappings, merging in the updated mappings when appropriate
        try (
            Reader reader = Files.newBufferedReader(originalPath);
            CSVParser originalParser = new CSVParser(reader, CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withHeader(SourceFilesInfo.CSV_HEADERS)
                    .withTrim());
            // Write to system.out if doing a dry run, otherwise write to mappings file
            BufferedWriter writer = options.getDryRun() ?
                    new BufferedWriter(new OutputStreamWriter(System.out)) :
                    Files.newBufferedWriter(mergedPath);
            CSVPrinter mergedPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT
                    .withHeader(SourceFilesInfo.CSV_HEADERS));
        ) {
            Set<String> origIds = new HashSet<>();
            for (CSVRecord originalRecord : originalParser) {
                GroupMapping origMapping = new GroupMapping();
                origMapping.setCdmId(originalRecord.get(0));
                origMapping.setGroupKey(originalRecord.get(1));

                GroupMapping updateMapping = updateInfo.getMappingByCdmId(origMapping.getCdmId());
                if (updateMapping == null) {
                    // No updates, so write original
                    writeMapping(mergedPrinter, origMapping);
                } else if (updateMapping.getGroupKey() != null) {
                    if (options.getForce() || origMapping.getGroupKey() == null) {
                        // overwrite entry with updated mapping if using force or original didn't have a group
                        writeMapping(mergedPrinter, updateMapping);
                    } else {
                        // retain original data
                        writeMapping(mergedPrinter, origMapping);
                    }
                } else {
                    // No change, retain original
                    writeMapping(mergedPrinter, origMapping);
                }
                origIds.add(originalRecord.get(0));
            }

            // Add in any records that were updated but not present in the original document
            Set<String> updateIds = updateInfo.getMappings().stream()
                    .map(GroupMapping::getCdmId).collect(Collectors.toSet());
            updateIds.removeAll(origIds);
            for (String id : updateIds) {
                writeMapping(mergedPrinter, updateInfo.getMappingByCdmId(id));
            }
        }

        // swap the merged mappings to be the main mappings, unless we're doing a dry run
        if (!options.getDryRun()) {
            Files.move(mergedPath, originalPath, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.delete(updatesPath);
    }

    private void writeMapping(CSVPrinter csvPrinter, GroupMapping mapping) throws IOException {
        csvPrinter.printRecord(mapping.getCdmId(), mapping.getGroupKey());
    }

    protected void setUpdatedDate(Instant timestamp) throws IOException {
        project.getProjectProperties().setGroupMappingsUpdatedDate(timestamp);
        ProjectPropertiesSerialization.write(project);
    }

    public GroupMappingInfo loadMappings() throws IOException {
        return loadMappings(project.getGroupMappingPath());
    }

    /**
     * @return the group mapping info for the provided project
     * @throws IOException
     */
    public GroupMappingInfo loadMappings(Path mappingPath) throws IOException {
        try (
            Reader reader = Files.newBufferedReader(mappingPath);
            CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withHeader(SourceFilesInfo.CSV_HEADERS)
                    .withTrim());
        ) {
            GroupMappingInfo info = new GroupMappingInfo();
            List<GroupMapping> mappings = new ArrayList<>();
            info.setMappings(mappings);
            for (CSVRecord csvRecord : csvParser) {
                GroupMapping mapping = new GroupMapping();
                mapping.setCdmId(csvRecord.get(0));
                String groupKey = csvRecord.get(1);
                if (!StringUtils.isBlank(groupKey)) {
                    mapping.setGroupKey(csvRecord.get(1));
                }
                mappings.add(mapping);
            }
            return info;
        }
    }

    public GroupMappingInfo loadGroupedMappings() throws IOException {
        return loadGroupedMappings(project.getGroupMappingPath());
    }

    /**
     * @return the group mapping info for the provided project, grouped by group key
     * @throws IOException
     */
    public GroupMappingInfo loadGroupedMappings(Path mappingPath) throws IOException {
        try (
            Reader reader = Files.newBufferedReader(project.getGroupMappingPath());
            CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .withHeader(SourceFilesInfo.CSV_HEADERS)
                    .withTrim());
        ) {
            GroupMappingInfo info = new GroupMappingInfo();
            Map<String, List<String>> mappings = info.getGroupedMappings();
            for (CSVRecord csvRecord : csvParser) {
                String id = csvRecord.get(0);
                String groupKey = csvRecord.get(1);
                if (StringUtils.isBlank(groupKey)) {
                    continue;
                }
                List<String> ids = mappings.computeIfAbsent(groupKey, v -> new ArrayList<String>());
                ids.add(id);
            }
            return info;
        }
    }


    public void setProject(MigrationProject project) {
        this.project = project;
    }

    public void setIndexService(CdmIndexService indexService) {
        this.indexService = indexService;
    }
}
