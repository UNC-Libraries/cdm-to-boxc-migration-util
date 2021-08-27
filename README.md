# CDM to Box-c Migration Utility
A commandline utility which facilitates the migration of collections from a CONTENTdm server to a Box-c repository.

# Building and developing

## Requirements
Requires Java 8 in order to run.
Building the project requires maven 3.
The project has only been tested in Mac and Linux environments

## Initial project setup
When first setting up the project for development purposes, you will need to perform the following steps:
```
# First, make a local clone of the box-c and build it
git clone git@github.com:UNC-Libraries/Carolina-Digital-Repository.git
cd Carolina-Digital-Repository
mvn clean install -DskipTests

cd ..

# Next, clone the migration utility and build it
git clone git@github.com:UNC-Libraries/cdm-to-boxc-migration-util.git
cd cdm-to-boxc-migration-util
mvn clean install
```

### Building and updating
In order to perform a full build of the project (with tests run) you would perform:
```
mvn clean package
```

If there are updates to the box-c project which need to be pulled in for use in the migration utility, you will need to do a `pull` in the box-c clone (or make the changes locally), and build the box-c project with a `mvn clean install -DskipTests`.

### Deploying
In order to deploy the project to a server or the development VM, see the `deploy_migration_util.rb` command from the `boxc-ansible` project. You can deploy uncommitted changes to the utility by providing the `-p` option. For example, `./deploy_migration_util.rb dev -p /path/to/cdm-to-boxc-migration-util` would build and deploy the current state of the migration util located at the provided path.

# Usage

## Basic Usage on Servers
```
cdm2bxc.sh -h
```

## General Workflow
1. Initialize a new migration project for a CDM collection
2. Export object records from CDM
3. Index the exported object records
4. Add data to the migration project, in any order:
	1. Map objects to Box-c destinations
	2. Add MODS descriptions for objects and new collections
	3. Map objects to source files
	4. Map objects to access files (optional)
5. Once all of these steps are complete and the migration team signs off...
6. Perform transformation of migration project to one or more SIPs for deposit (number of SIPs is based on the destination mappings)
7. Submit SIPs to Box-c deposit service for ingest

### Example workflow for Gilmer
```
# Initialize a new migration project (named gilmer_demo, from CDM collection gilmer)
cdm2bxc.sh init -p gilmer_demo -c gilmer
cd gilmer
# Export object records from CDM
cdm2bxc.sh export
# Index the exported object records
cdm2bxc.sh index
# Map objects to Box-c destiantion
cdm2bxc.sh destinations generate -dd <dest UUID>
# Map objects to source files
cdm2bxc.sh source_files generate -b /mnt/locos/shc/bucket/00276_gilmer_maps/ -p "([0-9]+)\\_([^_]+)\\_E.tiff?" -t '00$1_op0$2_0001.tif' -l
cdm2bxc.sh source_files generate -b /mnt/locos/shc/bucket/00276_gilmer_maps/enhanced/ -p "([0-9]+)\\_([^_]+)\\_E.tiff?" -t '00$1_op0$2_0001_e.tif' -l -u 
# Optional: Map objects to access files
cdm2bxc.sh access_files generate -b /mnt/locos/shc/bucket/00276_gilmer_maps/enhanced/ -p "([0-9]+)\\_([^_]+)\\_E.tiff?" -t '00$1_op0$2_0001_e.tif' -l -d

# Creation of MODS descriptions is not performed by the migration utility
# Descriptions for objects being migrated should be placed in the "descriptions" folder, encoded as modsCollections
# Descriptions for new collections should be placed in the "newCollectionDescriptions", encoded as mods records

# Perform transformation of migration project to one or more SIPs for deposit
cdm2bxc.sh descriptions expand
cdm2bxc.sh sips generate

# Submit all SIPs for deposit (optionally, individual SIPs can be submitted)
cdm2bxc.sh submit
# NOTE: in order to submit the SIPs, they must be located in an approved staging location.
# All prior steps can be perform at any path on the server.
```

### Monitoring progress and project state
In order to view the overall status of a migration project you may use the status command:
```
cdm2bxc.sh status
```
Additionally, there are a number of more detailed status reports available for individual components of the migration, such as:
```
cdm2bxc.sh descriptions status
cdm2bxc.sh source_files status
cdm2bxc.sh access_files status
cdm2bxc.sh destinations status
```

There are also commands available to validate various aspects of the project:
```
cdm2bxc.sh descriptions validate # validates MODS descriptions against schemas and local schematron
cdm2bxc.sh source_files validate # verifies syntax, whether mapped files exist, and other concerns
cdm2bxc.sh access_files validate
cdm2bxc.sh destinations validate # verifies syntax and consistency of mappings
```

# Project Directory Structure
A migration project initialized using this utility will produce a directory with the following structure:
* <project name> - root directory for the project, named using the project name provided during initialization
	* .project.json - Properties tracked by the migration utility about the current migration project.
	* access_files.csv - Produced by the `access_files` command. Maps CDM IDs to paths where access files to be migrated are located.
	* cdm_fields.csv - Produced by the `init` command. Contains the list of fields for the CDM collection being migrated. This file may be edited in order to rename or exclude fields prior to `export`. Nicknames and export names must be unique.
	* cdm_index.db - Produced by the `index` command. This is a sqlite3 database containing all of the exported data for the CDM collection being migrated. It can be edited, but should be handled with care as it will be used for almost all other commands.
	* descriptions/ - Directory in which user produced mods:modsCollection files should be placed, containing mods records for objects being migrated. The names of the files do not matter, except that they must be .xml files. The MODS records within the collections must contain a CDM IDs in the form `<mods:identifier type="local" displayLabel="CONTENTdm number">27</mods:identifier>`.
	* destinations.csv - Produced by the `destinations` command. CSV document which maps CDM ids to Box-c deposit destinations, and optionally, new collections.
	* exports/ - Directory generated by the `export` command. Contains one or more .xml files containing exported CDM records.
	* newCollectionDescriptions/ - Directory in which user produced mods:mods documents describing newly generated Box-c collections should be placed. The files must be named using the same identifier used for the new collections in the destination.csv file.
	* sips/ - Directory generated by the `sips` command. Contains submission information packages produced for submission to the repository. Within this directory will by subdirectories named based on the UUID of the deposit produced. The contents of these directories follows the standard box-c deposit pipeline layout.
	* source_files.csv - Produced by the `source_files` command. Maps CDM IDs to paths where source files to be migrated are located.