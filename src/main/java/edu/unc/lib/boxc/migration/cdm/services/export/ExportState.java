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

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Arrays;

/**
 * State of a CDM export operation
 *
 * @author bbpennel
 */
public class ExportState {
    private Instant startTime;
    private Integer exportPageSize;
    private Integer listIdPageSize;
    private Integer totalObjects;
    private int listedObjectCount;
    // Int so it will start at 0 instead of null
    private int lastExportedIndex;
    private boolean resuming = false;
    private ProgressState progressState;

    public enum ProgressState {
        STARTING, COUNT_COMPLETED, LISTING_OBJECTS, LISTING_COMPLETED, EXPORTING, EXPORT_COMPLETED;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Integer getExportPageSize() {
        return exportPageSize;
    }

    public void setExportPageSize(Integer exportPageSize) {
        this.exportPageSize = exportPageSize;
    }

    public Integer getListIdPageSize() {
        return listIdPageSize;
    }

    public void setListIdPageSize(Integer listIdPageSize) {
        this.listIdPageSize = listIdPageSize;
    }

    @JsonIgnore
    public int getListedObjectCount() {
        return listedObjectCount;
    }

    public void setListedObjectCount(int listedObjectCount) {
        this.listedObjectCount = listedObjectCount;
    }

    public void incrementListedObjectCount(int increVal) {
        this.listedObjectCount += increVal;
    }

    public Integer getTotalObjects() {
        return totalObjects;
    }

    public void setTotalObjects(Integer totalObjects) {
        this.totalObjects = totalObjects;
    }

    public ProgressState getProgressState() {
        return progressState;
    }

    public void setProgressState(ProgressState progressState) {
        this.progressState = progressState;
    }

    /**
     *
     * @param exportState
     * @param expectedStates
     * @return True if the progressState of the provided exportState matches any of the expected states
     */
    public static boolean inState(ExportState exportState, ProgressState... expectedStates) {
        if (exportState == null) {
            return false;
        }
        ProgressState pState = exportState.getProgressState();
        if (pState == null) {
            return false;
        }
        return Arrays.stream(expectedStates).anyMatch(s -> s.equals(pState));
    }

    public int getLastExportedIndex() {
        return lastExportedIndex;
    }

    public void setLastExportedIndex(int lastExportedIndex) {
        this.lastExportedIndex = lastExportedIndex;
    }

    @JsonIgnore
    public boolean isResuming() {
        return resuming;
    }

    public void setResuming(boolean resuming) {
        this.resuming = resuming;
    }
}
