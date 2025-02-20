/*
 * Copyright (C) 2017 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.plugins.packagemanagers.maven

import java.io.File

import org.apache.maven.project.ProjectBuildingResult

import org.eclipse.aether.graph.DependencyNode

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.model.DependencyGraph
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.createAndLogIssue
import org.ossreviewtoolkit.model.utils.DependencyGraphBuilder
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.LocalProjectWorkspaceReader
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.MavenDependencyHandler
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.MavenSupport
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.identifier
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.isTychoProject
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.toOrtProject
import org.ossreviewtoolkit.utils.common.searchUpwardsForSubdirectory

/**
 * The [Maven](https://maven.apache.org/) package manager for Java.
 */
class Maven(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(
    name,
    // The "options" convenience property from "PackageManager" is not available here yet.
    if (analyzerConfig.getPackageManagerConfiguration(name)?.options?.get("sbtMode").toBoolean()) "SBT" else "Maven",
    analysisRoot,
    analyzerConfig,
    repoConfig
) {
    class Factory : AbstractPackageManagerFactory<Maven>("Maven") {
        override val globsForDefinitionFiles = listOf("pom.xml")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Maven(type, analysisRoot, analyzerConfig, repoConfig)
    }

    private val mavenSupport = MavenSupport(LocalProjectWorkspaceReader { localProjectBuildingResults[it]?.pomFile })

    private val localProjectBuildingResults = mutableMapOf<String, ProjectBuildingResult>()

    /** The builder for the shared dependency graph. */
    private lateinit var graphBuilder: DependencyGraphBuilder<DependencyNode>

    private val sbtMode = options["sbtMode"].toBoolean()

    override fun beforeResolution(definitionFiles: List<File>) {
        localProjectBuildingResults += mavenSupport.prepareMavenProjects(definitionFiles)

        val localProjects = localProjectBuildingResults.mapValues { it.value.project }
        val dependencyHandler = MavenDependencyHandler(managerName, projectType, mavenSupport, localProjects, sbtMode)
        graphBuilder = DependencyGraphBuilder(dependencyHandler)
    }

    /**
     * Map the given [definitionFiles] to a list of files that should be processed. This implementation filters out
     * projects that require the Tycho build extension.
     */
    override fun mapDefinitionFiles(definitionFiles: List<File>): List<File> {
        val tychoRoots = definitionFiles.filter(::isTychoProject).map { it.parentFile }

        // All pom files under a Tycho project will be handled by Tycho and therefore need to be excluded.
        return definitionFiles.filterNot { file ->
            tychoRoots.any { file.startsWith(it) }
        }
    }

    override fun createPackageManagerResult(projectResults: Map<File, List<ProjectAnalyzerResult>>) =
        PackageManagerResult(projectResults, graphBuilder.build(), graphBuilder.packages())

    override fun resolveDependencies(definitionFile: File, labels: Map<String, String>): List<ProjectAnalyzerResult> {
        val workingDir = definitionFile.parentFile
        val projectBuildingResult = mavenSupport.buildMavenProject(definitionFile)
        val mavenProject = projectBuildingResult.project
        val projectId = mavenProject.identifier(projectType)
        val knownPackages = graphBuilder.packages()

        // If running in SBT mode expect that POM files were generated in a "target" subdirectory and that the correct
        // project directory is the parent directory of this.
        val projectDir = if (sbtMode) {
            workingDir.searchUpwardsForSubdirectory("target") ?: workingDir
        } else {
            workingDir
        }

        projectBuildingResult.dependencies.filterNot {
            excludes.isScopeExcluded(it.dependency.scope)
        }.forEach { node ->
            graphBuilder.addDependency(DependencyGraph.qualifyScope(projectId, node.dependency.scope), node)
        }

        val project = mavenProject.toOrtProject(
            projectId,
            definitionFile,
            projectDir,
            graphBuilder.scopesFor(projectId)
        )

        val issues = (graphBuilder.packages() - knownPackages).mapNotNull { pkg ->
            if (pkg.description == "POM was created by Sonatype Nexus") {
                createAndLogIssue(
                    managerName,
                    "Package '${pkg.id.toCoordinates()}' seems to use an auto-generated POM which might lack metadata.",
                    Severity.HINT
                )
            } else {
                null
            }
        }

        return listOf(ProjectAnalyzerResult(project, emptySet(), issues))
    }

    override fun afterResolution(definitionFiles: List<File>) {
        mavenSupport.close()
    }
}

/**
 * Convenience extension property to obtain all the [DependencyNode]s from this [ProjectBuildingResult].
 */
private val ProjectBuildingResult.dependencies: List<DependencyNode>
    get() = dependencyResolutionResult.dependencyGraph.children
