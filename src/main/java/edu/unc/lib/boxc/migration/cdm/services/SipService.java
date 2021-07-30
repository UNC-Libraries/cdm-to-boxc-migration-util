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

import static edu.unc.lib.boxc.model.api.DatastreamType.ORIGINAL_FILE;
import static edu.unc.lib.boxc.model.api.rdf.CdrDeposit.stagingLocation;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.rdf.model.Bag;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;

import edu.unc.lib.boxc.deposit.impl.model.DepositDirectoryManager;
import edu.unc.lib.boxc.deposit.impl.model.DepositModelHelpers;
import edu.unc.lib.boxc.deposit.impl.model.DepositModelManager;
import edu.unc.lib.boxc.migration.cdm.exceptions.InvalidProjectStateException;
import edu.unc.lib.boxc.migration.cdm.exceptions.MigrationException;
import edu.unc.lib.boxc.migration.cdm.model.CdmFieldInfo;
import edu.unc.lib.boxc.migration.cdm.model.DestinationsInfo;
import edu.unc.lib.boxc.migration.cdm.model.DestinationsInfo.DestinationMapping;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProjectProperties;
import edu.unc.lib.boxc.migration.cdm.model.SourceFilesInfo;
import edu.unc.lib.boxc.migration.cdm.model.SourceFilesInfo.SourceFileMapping;
import edu.unc.lib.boxc.migration.cdm.options.SipGenerationOptions;
import edu.unc.lib.boxc.migration.cdm.util.CLIConstants;
import edu.unc.lib.boxc.model.api.DatastreamType;
import edu.unc.lib.boxc.model.api.SoftwareAgentConstants.SoftwareAgent;
import edu.unc.lib.boxc.model.api.ids.PID;
import edu.unc.lib.boxc.model.api.ids.PIDMinter;
import edu.unc.lib.boxc.model.api.rdf.Cdr;
import edu.unc.lib.boxc.model.api.rdf.CdrDeposit;
import edu.unc.lib.boxc.model.api.rdf.Premis;
import edu.unc.lib.boxc.model.fcrepo.ids.AgentPids;
import edu.unc.lib.boxc.operations.api.events.PremisLogger;
import edu.unc.lib.boxc.operations.api.events.PremisLoggerFactory;

/**
 * Service for generation and access to migration SIPs
 *
 * @author bbpennel
 */
public class SipService {
    private static final Logger log = getLogger(SipService.class);
    public static final String SIP_TDB_PATH = "tdb_model";
    public static final String MODEL_EXPORT_NAME = "model.n3";

    private CdmIndexService indexService;
    private SourceFileService sourceFileService;
    private AccessFileService accessFileService;
    private DescriptionsService descriptionsService;
    private PremisLoggerFactory premisLoggerFactory;
    private MigrationProject project;
    private PIDMinter pidMinter;

    private Map<String, DestinationSipEntry> cdmId2DestMap;
    private List<DestinationSipEntry> destEntries = new ArrayList<>();

    public SipService() {
    }

    /**
     * Generate SIPS for each destination mapped in this project
     * @return list of sip objects generated
     */
    public List<MigrationSip> generateSips(SipGenerationOptions options) {
        validateProjectState();
        initializeDestinations();

        Connection conn = null;
        try {
            SourceFilesInfo sourceFilesInfo = sourceFileService.loadMappings();
            SourceFilesInfo accessFilesInfo = null;
            try {
                accessFilesInfo = accessFileService.loadMappings();
            } catch (NoSuchFileException e) {
                log.debug("No access mappings file, no access files will be added to the SIP");
            }

            conn = indexService.openDbConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("select " + CdmFieldInfo.CDM_ID + "," + CdmFieldInfo.CDM_CREATED
                    + " from " + CdmIndexService.TB_NAME);
            while (rs.next()) {
                String cdmId = rs.getString(1);
                String cdmCreated = rs.getString(2) + "T00:00:00.000Z";
                DestinationSipEntry destEntry = getDestinationEntry(cdmId);
                Bag destBag = destEntry.getDestinationBag();
                Model model = destBag.getModel();
                Path expDescPath = descriptionsService.getExpandedDescriptionFilePath(cdmId);
                if (Files.notExists(expDescPath)) {
                    String message = "Cannot transform object " + cdmId + ", it does not have a MODS description";
                    if (options.isForce()) {
                        CLIConstants.outputLogger.info(message);
                        continue;
                    } else {
                        throw new InvalidProjectStateException(message);
                    }
                }
                SourceFileMapping sourceMapping = sourceFilesInfo.getMappingByCdmId(cdmId);
                if (sourceMapping == null || sourceMapping.getSourcePath() == null) {
                    String message = "Cannot transform object " + cdmId + ", no source file has been mapped";
                    if (options.isForce()) {
                        CLIConstants.outputLogger.info(message);
                        continue;
                    } else {
                        throw new InvalidProjectStateException(message);
                    }
                }

                PID workPid = pidMinter.mintContentPid();
                log.info("Transforming CDM object {} to box-c work {}", cdmId, workPid.getId());
                Bag workBag = model.createBag(workPid.getRepositoryPath());
                workBag.addProperty(RDF.type, Cdr.Work);
                workBag.addLiteral(CdrDeposit.createTime, cdmCreated);

                // Copy description to SIP
                Path sipDescPath = destEntry.depositDirManager.getModsPath(workPid);
                Files.copy(expDescPath, sipDescPath);

                // Create FileObject
                PID fileObjPid = pidMinter.mintContentPid();
                Resource fileObjResc = model.getResource(fileObjPid.getRepositoryPath());
                fileObjResc.addProperty(RDF.type, Cdr.FileObject);
                fileObjResc.addLiteral(CdrDeposit.createTime, cdmCreated);

                workBag.add(fileObjResc);
                workBag.addProperty(Cdr.primaryObject, fileObjResc);

                // Link source file
                Resource origResc = DepositModelHelpers.addDatastream(fileObjResc, ORIGINAL_FILE);
                origResc.addLiteral(stagingLocation, sourceMapping.getSourcePath().toUri().toString());

                // Link access file
                if (accessFilesInfo != null) {
                    SourceFileMapping accessMapping = accessFilesInfo.getMappingByCdmId(cdmId);
                    if (accessMapping != null && accessMapping.getSourcePath() != null) {
                        Resource accessResc = DepositModelHelpers.addDatastream(
                                fileObjResc, DatastreamType.ACCESS_COPY);
                        accessResc.addLiteral(stagingLocation, accessMapping.getSourcePath().toUri().toString());
                        String mimetype = accessFileService.getMimetype(accessMapping.getSourcePath());
                        accessResc.addLiteral(CdrDeposit.mimetype, mimetype);
                    }
                }

                // Generate migration PREMIS event
                Path premisPath = destEntry.depositDirManager.getPremisPath(workPid);
                PremisLogger premisLogger = premisLoggerFactory.createPremisLogger(workPid, premisPath.toFile());
                premisLogger.buildEvent(Premis.Ingestion)
                        .addEventDetail("Object migrated as a part of the CONTENTdm to Box-c 5 migration")
                        .addSoftwareAgent(AgentPids.forSoftware(SoftwareAgent.migrationUtil))
                        .writeAndClose();

                // Add work to deposit or new collection
                destBag.add(workBag);
            }

            // Finalize all of the SIPs by closing and exporting their models
            List<MigrationSip> sips = new ArrayList<>();
            for (DestinationSipEntry entry : destEntries) {
                entry.commitModel();
                exportDepositModel(entry);
                sips.add(new MigrationSip(entry));
                // Cleanup the TDB directory not that it has been exported
                FileUtils.deleteDirectory(entry.getTdbPath().toFile());
            }
            return sips;
        } catch (SQLException | IOException e) {
            throw new MigrationException("Failed to generate SIP", e);
        } finally {
            CdmIndexService.closeDbConnection(conn);
            destEntries.stream().forEach(DestinationSipEntry::close);
        }
    }

    private void exportDepositModel(DestinationSipEntry entry) throws IOException {
        Model model = entry.depositModelManager.getReadModel(entry.getDepositPid());
        Path modelExportPath = entry.depositDirManager.getDepositDir().resolve(MODEL_EXPORT_NAME);
        Writer writer = Files.newBufferedWriter(modelExportPath);
        model.write(writer, "N3");
        entry.depositModelManager.close();
    }

    private void validateProjectState() {
        MigrationProjectProperties props = project.getProjectProperties();
        if (props.getIndexedDate() == null) {
            throw new InvalidProjectStateException("Exported data must be indexed");
        }
        if (props.getDestinationsGeneratedDate() == null) {
            throw new InvalidProjectStateException("Destinations must be mapped");
        }
        if (props.getDescriptionsExpandedDate() == null) {
            throw new InvalidProjectStateException("Descriptions must be created and expanded");
        }
        if (props.getSourceFilesUpdatedDate() == null) {
            throw new InvalidProjectStateException("Source files must be mapped");
        }
    }

    private DestinationSipEntry getDestinationEntry(String cdmId) {
        DestinationSipEntry entry = cdmId2DestMap.get(cdmId);
        if (entry == null) {
            return cdmId2DestMap.get(DestinationsInfo.DEFAULT_ID);
        } else {
            return entry;
        }
    }

    private void initializeDestinations() {
        try {
            DestinationsInfo destInfo = DestinationsService.loadMappings(project);

            cdmId2DestMap = new HashMap<>();
            Map<String, DestinationSipEntry> destMap = new HashMap<>();
            for (DestinationMapping mapping : destInfo.getMappings()) {
                String key = !StringUtils.isBlank(mapping.getCollectionId()) ?
                        mapping.getCollectionId() : mapping.getDestination();
                // Retrieve existing destination entry or generate new one if this is first encounter
                DestinationSipEntry destEntry = destMap.computeIfAbsent(key, k -> {
                    PID depositPid = pidMinter.mintDepositRecordPid();
                    log.info("Initializing SIP for deposit {} to destination {}",
                            depositPid.getId(), mapping.getDestination());
                    DestinationSipEntry entry = new DestinationSipEntry(depositPid, mapping);
                    entry.initializeDepositModel();
                    destEntries.add(entry);
                    return entry;
                });

                cdmId2DestMap.put(mapping.getId(), destEntry);
            }
        } catch (IOException e) {
            throw new MigrationException("Failed to load destination mappings", e);
        }
    }

    private class DestinationSipEntry {
        private PID depositPid;
        private PID newCollectionPid;
        private String newCollectionId;
        private DepositModelManager depositModelManager;
        private DepositDirectoryManager depositDirManager;
        private Model writeModel;

        public DestinationSipEntry(PID depositPid, DestinationMapping mapping) {
            this.depositPid = depositPid;
            this.depositDirManager = new DepositDirectoryManager(depositPid, project.getSipsPath(), true);
            if (!StringUtils.isBlank(mapping.getCollectionId())) {
                this.newCollectionPid = pidMinter.mintContentPid();
                this.newCollectionId = mapping.getCollectionId();
                log.info("Generated new collection {} from id {}", newCollectionPid.getId(), mapping.getCollectionId());
            }
            this.depositModelManager = new DepositModelManager(getTdbPath());
        }

        public void initializeDepositModel() {
            Model model = getWriteModel();
            Bag depRootBag = model.createBag(depositPid.getRepositoryPath());
            // Populate the new collection object
            if (newCollectionPid != null) {
                Bag newCollBag = model.createBag(newCollectionPid.getRepositoryPath());
                depRootBag.add(newCollBag);
                newCollBag.addProperty(RDF.type, Cdr.Collection);
                newCollBag.addLiteral(CdrDeposit.label, newCollectionId);
            }
            commitModel();
        }

        public Path getTdbPath() {
            return depositDirManager.getDepositDir().resolve(SIP_TDB_PATH);
        }

        public Model getWriteModel() {
            if (writeModel == null) {
                writeModel = depositModelManager.getWriteModel(depositPid);
            }
            return writeModel;
        }

        public void commitModel() {
            depositModelManager.commit();
            writeModel = null;
        }

        public void close() {
            depositModelManager.close();
        }

        public Bag getDestinationBag() {
            if (newCollectionPid != null) {
                return getWriteModel().getBag(newCollectionPid.getRepositoryPath());
            } else {
                return getWriteModel().getBag(depositPid.getRepositoryPath());
            }
        }

        public PID getDepositPid() {
            return depositPid;
        }

        public PID getNewCollectionPid() {
            return newCollectionPid;
        }
    }

    /**
     * Object representing a migration SIP
     * @author bbpennel
     */
    public static class MigrationSip {
        private PID depositPid;
        private PID newCollectionPid;
        private Path sipPath;

        public MigrationSip(DestinationSipEntry entry) {
            this.depositPid = entry.getDepositPid();
            this.sipPath = entry.depositDirManager.getDepositDir();
            this.newCollectionPid = entry.getNewCollectionPid();
        }

        /**
         * @return PID of the deposit contained within this SIP
         */
        public PID getDepositPid() {
            return depositPid;
        }

        public void setDepositPid(PID depositPid) {
            this.depositPid = depositPid;
        }

        /**
         * @return PID of the new collection being created by this SIP, if one was created
         */
        public PID getNewCollectionPid() {
            return newCollectionPid;
        }

        public void setNewCollectionPid(PID newCollectionPid) {
            this.newCollectionPid = newCollectionPid;
        }

        /**
         * @return Path to the directory containing the SIP
         */
        public Path getSipPath() {
            return sipPath;
        }

        public void setSipPath(Path sipPath) {
            this.sipPath = sipPath;
        }

        /**
         * @return Path to the file containing the serialized deposit model for this SIP
         */
        public Path getModelPath() {
            return sipPath.resolve(MODEL_EXPORT_NAME);
        }
    }

    public void setIndexService(CdmIndexService indexService) {
        this.indexService = indexService;
    }

    public void setSourceFileService(SourceFileService sourceFileService) {
        this.sourceFileService = sourceFileService;
    }

    public void setAccessFileService(AccessFileService accessFileService) {
        this.accessFileService = accessFileService;
    }

    public void setDescriptionsService(DescriptionsService descriptionsService) {
        this.descriptionsService = descriptionsService;
    }

    public void setPremisLoggerFactory(PremisLoggerFactory premisLoggerFactory) {
        this.premisLoggerFactory = premisLoggerFactory;
    }

    public void setPidMinter(PIDMinter pidMinter) {
        this.pidMinter = pidMinter;
    }

    public void setProject(MigrationProject project) {
        this.project = project;
    }
}
