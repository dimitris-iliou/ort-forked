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

import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.model.DependencyGraph
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.utils.DependencyGraphBuilder
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.LocalProjectWorkspaceReader
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.MavenDependencyHandler
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.MavenSupport
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.createIssuesForAutoGeneratedPoms
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.identifier
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.isTychoProject
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.toOrtProject
import org.ossreviewtoolkit.utils.common.searchUpwardsForSubdirectory

/**
 * The [Maven](https://maven.apache.org/) package manager for Java.
 */
@OrtPlugin(
    displayName = "Maven",
    description = "The Maven package manager for Java.",
    factory = PackageManagerFactory::class
)
class Maven(override val descriptor: PluginDescriptor = MavenFactory.descriptor, private val sbtMode: Boolean) :
    PackageManager(if (sbtMode) "SBT" else "Maven") {
    constructor(descriptor: PluginDescriptor = MavenFactory.descriptor) : this(descriptor, false)

    override val globsForDefinitionFiles = listOf("pom.xml")

    private val mavenSupport = MavenSupport(LocalProjectWorkspaceReader { localProjectBuildingResults[it]?.pomFile })

    private val localProjectBuildingResults = mutableMapOf<String, ProjectBuildingResult>()

    /** The builder for the shared dependency graph. */
    private lateinit var graphBuilder: DependencyGraphBuilder<DependencyNode>

    override fun beforeResolution(
        analysisRoot: File,
        definitionFiles: List<File>,
        analyzerConfig: AnalyzerConfiguration
    ) {
        localProjectBuildingResults += mavenSupport.prepareMavenProjects(definitionFiles)

        val localProjects = localProjectBuildingResults.mapValues { it.value.project }
        val dependencyHandler = MavenDependencyHandler(
            descriptor.displayName,
            projectType,
            localProjects,
            mavenSupport.defaultPackageResolverFun(sbtMode)
        )
        graphBuilder = DependencyGraphBuilder(dependencyHandler)
    }

    /**
     * Map the given [definitionFiles] to a list of files that should be processed. This implementation filters out
     * projects that require the Tycho build extension.
     */
    override fun mapDefinitionFiles(analysisRoot: File, definitionFiles: List<File>): List<File> {
        val tychoRoots = definitionFiles.filter(::isTychoProject).map { it.parentFile }

        // All pom files under a Tycho project will be handled by Tycho and therefore need to be excluded.
        return definitionFiles.filterNot { file ->
            tychoRoots.any { file.startsWith(it) }
        }
    }

    override fun createPackageManagerResult(projectResults: Map<File, List<ProjectAnalyzerResult>>) =
        PackageManagerResult(projectResults, graphBuilder.build(), graphBuilder.packages())

    override fun resolveDependencies(
        analysisRoot: File,
        definitionFile: File,
        excludes: Excludes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): List<ProjectAnalyzerResult> {
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

        val issues = (graphBuilder.packages() - knownPackages).createIssuesForAutoGeneratedPoms(descriptor.displayName)
        return listOf(ProjectAnalyzerResult(project, emptySet(), issues))
    }

    override fun afterResolution(analysisRoot: File, definitionFiles: List<File>) {
        mavenSupport.close()
    }
}

/**
 * Convenience extension property to obtain all the [DependencyNode]s from this [ProjectBuildingResult].
 */
private val ProjectBuildingResult.dependencies: List<DependencyNode>
    get() = dependencyResolutionResult.dependencyGraph.children
