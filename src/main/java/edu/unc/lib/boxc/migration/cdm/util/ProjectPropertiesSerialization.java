package edu.unc.lib.boxc.migration.cdm.util;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProjectProperties;

/**
 * Utility methods for serializing and deserializing MigrationProject objects
 * @author bbpennel
 */
public class ProjectPropertiesSerialization {
    private static final ObjectReader PROJECT_READER;
    private static final ObjectWriter PROJECT_WRITER;
    static {
        JavaTimeModule module = new JavaTimeModule();
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(module);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        PROJECT_READER = mapper.readerFor(MigrationProjectProperties.class);
        PROJECT_WRITER = mapper.writerFor(MigrationProjectProperties.class);
    }

    private ProjectPropertiesSerialization() {
    }

    /**
     * @param path
     * @return MigrationProjectProperties object read from the provided path
     * @throws IOException
     */
    public static MigrationProjectProperties read(Path path) throws IOException {
        return PROJECT_READER.readValue(path.toFile());
    }

    /**
     * Serializes the provided MigrationProjectProperties to a file at the given path
     * @param path
     * @param project
     * @throws IOException
     */
    public static void write(Path path, MigrationProjectProperties properties) throws IOException {
        PROJECT_WRITER.writeValue(path.toFile(), properties);
    }

    /**
     * Serializes the provided MigrationProject to the appropriate path
     * @param project
     * @throws IOException
     */
    public static void write(MigrationProject project) throws IOException {
        write(project.getProjectPropertiesPath(), project.getProjectProperties());
    }
}
