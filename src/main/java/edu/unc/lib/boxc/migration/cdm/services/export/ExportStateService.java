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
package edu.unc.lib.boxc.migration.cdm.services.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.unc.lib.boxc.migration.cdm.exceptions.InvalidProjectStateException;
import edu.unc.lib.boxc.migration.cdm.model.MigrationProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static edu.unc.lib.boxc.migration.cdm.services.export.ExportState.ProgressState;

/**
 * @author bbpennel
 */
public class ExportStateService {
    private static final String IDS_FILENAME = ".object_ids.txt";
    private static final String STATE_FILENAME = ".export_state.json";
    private static final ObjectWriter STATE_WRITER;
    private static final ObjectReader STATE_READER;
    static {
        JavaTimeModule module = new JavaTimeModule();
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(module);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        STATE_READER = mapper.readerFor(ExportState.class);
        STATE_WRITER = mapper.writerFor(ExportState.class);
    }

    private MigrationProject project;
    private ExportState state = new ExportState();

    public void startOrResumeExport(boolean forceRestart) throws IOException {
        if (forceRestart) {
            clearState();
            transitionToStarting();
            return;
        }
        state = readState();
        ProgressState progressState = state.getProgressState();
        if (progressState == null) {
            transitionToStarting();
            return;
        }
        // If resuming in initial steps, start over
        if (ProgressState.STARTING.equals(progressState) || ProgressState.COUNT_COMPLETED.equals(progressState)) {
            clearState();
            transitionToStarting();
            return;
        }
        if (ProgressState.EXPORT_COMPLETED.equals(progressState)) {
            throw new InvalidProjectStateException("Export has already completed, must force restart to overwrite");
        }
        // For all other states, we are resuming
        state.setResuming(true);
    }

    /**
     * Check if the export is either not resuming or is in one of the listed states. Used to determine if
     * the steps for a stage of an export should be executed or not.
     * @param states
     * @return
     */
    public boolean inStateOrNotResuming(ProgressState... states) {
        if (!state.isResuming()) {
            return true;
        }
        return Arrays.stream(states).anyMatch(s -> s.equals(state.getProgressState()));
    }

    public boolean isResuming() {
        return state.isResuming();
    }

    /**
     * Transition the export to the listing object ids state
     * @throws IOException
     */
    public void transitionToStarting() throws IOException {
        state.setProgressState(ProgressState.STARTING);
        writeState();
    }

    /**
     * Record the count of objects in this collection has completed
     * @param count
     * @throws IOException
     */
    public void objectCountCompleted(int count) throws IOException {
        assertState(ProgressState.STARTING);
        state.setTotalObjects(count);
        state.setProgressState(ExportState.ProgressState.COUNT_COMPLETED);
        writeState();
    }

    /**
     * Transition the export to the listing object ids state
     * @param listingPageSize
     * @throws IOException
     */
    public void transitionToListing(int listingPageSize) throws IOException {
        assertState(ProgressState.COUNT_COMPLETED);
        state.setProgressState(ProgressState.LISTING_OBJECTS);
        state.setListIdPageSize(listingPageSize);
        writeState();
    }

    /**
     * Indicate that listing of object ids has completed
     * @throws IOException
     */
    public void listingComplete() throws IOException {
        assertState(ProgressState.LISTING_OBJECTS);
        state.setProgressState(ProgressState.LISTING_COMPLETED);
        writeState();
    }

    /**
     * Record a list of object ids which are included in this export
     * @param ids
     */
    public void registerObjectIds(List<String> ids) throws IOException {
        assertState(ProgressState.LISTING_OBJECTS);
        // Join to line separated, with trailing newline so that the next page starts on a new line
        String idsJoined = String.join("\r\n", ids) + "\r\n";
        Files.write(
                getObjectListPath(),
                idsJoined.getBytes(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * @return the list of object ids for this CDM project
     */
    public List<String> retrieveObjectIds() throws IOException {
        try (Stream<String> lines = Files.lines(getObjectListPath())) {
            return lines.collect(Collectors.toList());
        }
    }

    /**
     * Transition the export to the exporting objects state
     * @param exportPageSize
     * @throws IOException
     */
    public void transitionToExporting(int exportPageSize) throws IOException {
        assertState(ProgressState.LISTING_COMPLETED);
        state.setProgressState(ProgressState.EXPORTING);
        state.setExportPageSize(exportPageSize);
        writeState();
    }

    /**
     * Register a list of object ids as having been exported
     * @param objectIds
     * @throws IOException
     */
    public void registerExported(List<String> objectIds) throws IOException {
        assertState(ProgressState.EXPORTING);
        int lastIndex;
        if (state.getLastExportedIndex() == null) {
            // Subtract 1 from size to shift to 0 based index for the first page
            lastIndex = objectIds.size() - 1;
        } else {
            lastIndex = state.getLastExportedIndex() + objectIds.size();
        }
        state.setLastExportedIndex(lastIndex);
        writeState();
    }

    /**
     * Indicate that the export step has completed
     * @throws IOException
     */
    public void exportingCompleted() throws IOException {
        assertState(ProgressState.EXPORTING);
        state.setProgressState(ProgressState.EXPORT_COMPLETED);
        writeState();
    }

    public Path getObjectListPath() {
        return project.getExportPath().resolve(IDS_FILENAME);
    }

    public Path getExportStatePath() {
        return project.getExportPath().resolve(STATE_FILENAME);
    }

    public void assertState(ProgressState expectedState) {
        if (!state.getProgressState().equals(expectedState)) {
            throw new InvalidProjectStateException("Invalid state, export must be in " + expectedState + " state");
        }
    }

    /**
     * Serialize the state of the export operation
     * @throws IOException
     */
    public void writeState() throws IOException {
        if (Files.notExists(project.getExportPath())) {
            Files.createDirectories(project.getExportPath());
        }
        STATE_WRITER.writeValue(getExportStatePath().toFile(), state);
    }

    /**
     * @return Deserialized state of the export operation
     * @throws IOException
     */
    public ExportState readState() throws IOException {
        if (Files.notExists(getExportStatePath())) {
            return new ExportState();
        }
        return STATE_READER.readValue(getExportStatePath().toFile());
    }

    /**
     * Clear any existing export state for the project
     * @throws IOException
     */
    public void clearState() throws IOException {
        Files.deleteIfExists(getExportStatePath());
        Files.deleteIfExists(getObjectListPath());
        state = new ExportState();
    }

    public void setProject(MigrationProject project) {
        this.project = project;
    }

    public ExportState getState() {
        return state;
    }
}
