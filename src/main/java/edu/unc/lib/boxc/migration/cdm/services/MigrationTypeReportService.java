package edu.unc.lib.boxc.migration.cdm.services;

import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.util.PostMigrationReportConstants;
import edu.unc.lib.boxc.model.api.ResourceType;
import org.apache.commons.csv.CSVParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Service for counting new migrated objects in box-c (works and files)
 * @author krwong
 */
public class MigrationTypeReportService {
    private static final Logger log = getLogger(MigrationTypeReportService.class);

    private MigrationProject project;

    /**
     * @return count new works
     */
    public long countWorks() throws IOException {
        long numWorks;
        try (var csvParser = openCsvParser()) {
            numWorks = csvParser.stream()
                    .map(row -> row.get("boxc_obj_type"))
                    .filter(f -> f.toLowerCase().contains("work") || f.contains(ResourceType.Work.name()))
                    .count();
        }
        return numWorks;
    }

    /**
     * @return count new files
     */
    public long countFiles() throws IOException {
        long numFiles;
        try (var csvParser = openCsvParser()) {
            numFiles = csvParser.stream()
                    .map(row -> row.get("boxc_obj_type"))
                    .filter(f -> f.toLowerCase().contains("file") || f.contains(ResourceType.File.name()))
                    .count();
        }
        return numFiles;
    }

    private CSVParser openCsvParser() throws IOException {
        Reader reader = Files.newBufferedReader(project.getPostMigrationReportPath());
        return new CSVParser(reader, PostMigrationReportConstants.CSV_PARSER_FORMAT);
    }

    public void setProject(MigrationProject project) {
        this.project = project;
    }

}
