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
package edu.unc.lib.boxc.migration.cdm.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Destination mapping information for a project
 * @author bbpennel
 */
public class DestinationsInfo {
    public static final String DEFAULT_ID = "default";

    private List<DestinationMapping> mappings;

    public DestinationsInfo() {
        mappings = new ArrayList<>();
    }

    public List<DestinationMapping> getMappings() {
        return mappings;
    }

    public void setMappings(List<DestinationMapping> mappings) {
        this.mappings = mappings;
    }

    /**
     * An individual destination mapping
     * @author bbpennel
     */
    public static class DestinationMapping {
        private String id;
        private String destination;
        private String collectionId;

        public DestinationMapping() {
        }

        public DestinationMapping(String id, String destination, String collectionId) {
            this.id = id;
            this.destination = destination;
            this.collectionId = collectionId;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDestination() {
            return destination;
        }

        public void setDestination(String destination) {
            this.destination = destination;
        }

        public String getCollectionId() {
            return collectionId;
        }

        public void setCollectionId(String collectionId) {
            this.collectionId = collectionId;
        }
    }
}
