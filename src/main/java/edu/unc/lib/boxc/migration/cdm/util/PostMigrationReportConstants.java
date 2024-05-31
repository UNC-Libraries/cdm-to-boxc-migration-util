package edu.unc.lib.boxc.migration.cdm.util;

import org.apache.commons.csv.CSVFormat;

/**
 * Constants related to the post migration report
 *
 * @author bbpennel
 */
public class PostMigrationReportConstants {
    public static final String BXC_URL_HEADER = "boxc_url";
    public static final String VERIFIED_HEADER = "verified";
    public static final int VERIFIED_INDEX = 7;
    public static final String[] CSV_HEADERS = new String[] {
            "cdm_id", "cdm_url", "boxc_obj_type", "boxc_url", "boxc_title", "matching_value", "source_file",
            VERIFIED_HEADER, "boxc_parent_work_url", "boxc_parent_work_title", "children_count" };
    public static final CSVFormat CSV_OUTPUT_FORMAT = CSVFormat.Builder.create()
            .setHeader(CSV_HEADERS)
            .build();
    public static final CSVFormat CSV_PARSER_FORMAT = CSVFormat.Builder.create()
                .setSkipHeaderRecord(true)
                .setHeader(CSV_HEADERS)
                .setTrim(true)
                .build();

    private PostMigrationReportConstants() {
    }
}
