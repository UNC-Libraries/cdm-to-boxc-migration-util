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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import edu.unc.lib.boxc.migration.cdm.options.Verbosity;
import edu.unc.lib.boxc.migration.cdm.util.BannerUtility;
import edu.unc.lib.boxc.migration.cdm.util.CLIConstants;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * Main class for the CLI utils
 *
 * @author bbpennel
 *
 */
@Command(subcommands = {
        InitializeProjectCommand.class,
        CdmFieldsCommand.class,
        CdmExportCommand.class,
        CdmIndexCommand.class,
        DestinationsCommand.class,
        SourceFilesCommand.class,
        AccessFilesCommand.class,
        DescriptionsCommand.class,
        SipsCommand.class,
        SubmitSipsCommand.class,
        StatusCommand.class
    })
public class CLIMain implements Callable<Integer> {
    @Option(names = { "-w", "--work-dir" },
            scope = ScopeType.INHERIT,
            description = "Directory which the operations will happen relative to. Defaults to the current directory")
    private String workingDirectory;

    @Option(names = { "-v", "--verbose" },
            scope = ScopeType.INHERIT,
            description = "Set output to verbose level of verbosity")
    private boolean verbose;

    @Option(names = { "-q", "--quiet" },
            scope = ScopeType.INHERIT,
            description = "Set output to quiet level of verbosity")
    private boolean quiet;

    /**
     * @return Get the effective working directory
     */
    protected Path getWorkingDirectory() {
        Path currentPath = Paths.get(workingDirectory == null ? "." : workingDirectory);
        return currentPath.toAbsolutePath().normalize();
    }

    /**
     * @return Verbosity level to use with this command
     */
    protected Verbosity getVerbosity() {
        if (verbose) {
            return Verbosity.VERBOSE;
        }
        if (quiet) {
            return Verbosity.QUIET;
        }
        return Verbosity.NORMAL;
    }

    protected CLIMain() {
    }

    @Override
    public Integer call() throws Exception {
        CLIConstants.outputLogger.info(BannerUtility.getBanner());
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CLIMain()).execute(args);
        System.exit(exitCode);
    }

}
