/*
 * Copyright (C) 2025 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.Manifest

import kotlin.io.path.invariantSeparatorsPathString

import org.apache.logging.log4j.kotlin.logger
import org.apache.maven.AbstractMavenLifecycleParticipant
import org.apache.maven.cli.MavenCli
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Scm
import org.apache.maven.project.MavenProject

import org.codehaus.plexus.PlexusContainer
import org.codehaus.plexus.classworlds.ClassWorld

import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.graph.DependencyNode

import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.analyzer.ProjectResults
import org.ossreviewtoolkit.model.DependencyGraph
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.createAndLogIssue
import org.ossreviewtoolkit.model.utils.DependencyGraphBuilder
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.LocalProjectWorkspaceReader
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.LocalRepositoryHelper
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.MavenDependencyHandler
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.MavenSupport
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.P2ArtifactTracker
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.PackageResolverFun
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.createIssuesForAutoGeneratedPoms
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.identifier
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.internalId
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.isTychoProject
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.parseDependencyTree
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.parseScm
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.processDeclaredLicenses
import org.ossreviewtoolkit.plugins.packagemanagers.maven.utils.toOrtProject
import org.ossreviewtoolkit.utils.ort.createOrtTempFile

/**
 * A package manager implementation supporting Maven projects using [Tycho](https://github.com/eclipse-tycho/tycho).
 */
@OrtPlugin(
    displayName = "Tycho",
    description = "The Tycho package manager for Maven projects.",
    factory = PackageManagerFactory::class
)
class Tycho(override val descriptor: PluginDescriptor = TychoFactory.descriptor) : PackageManager("Tycho") {
    override val globsForDefinitionFiles = listOf("pom.xml")

    /**
     * The builder to generate the dependency graph. This could actually be a local variable, but it is also needed
     * to construct the final [PackageManagerResult].
     */
    private lateinit var graphBuilder: DependencyGraphBuilder<DependencyNode>

    override fun mapDefinitionFiles(analysisRoot: File, definitionFiles: List<File>): List<File> =
        definitionFiles.filter(::isTychoProject)

    override fun resolveDependencies(
        analysisRoot: File,
        definitionFile: File,
        excludes: Excludes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): List<ProjectAnalyzerResult> {
        logger.info { "Resolving Tycho dependencies for $definitionFile." }

        val repositoryHelper = LocalRepositoryHelper()
        val collector = TychoProjectsCollector()
        val tracker = P2ArtifactTracker(repositoryHelper)
        val (exitCode, buildLog) = runBuild(
            analysisRoot,
            collector,
            tracker,
            definitionFile.parentFile,
            excludes,
            analyzerConfig.skipExcluded
        )

        val resolvedProjects = createMavenSupport(collector).use { mavenSupport ->
            graphBuilder = createGraphBuilder(mavenSupport, collector.mavenProjects, tracker, repositoryHelper)

            buildLog.inputStream().use { stream ->
                parseDependencyTree(stream, collector.mavenProjects.values).map { projectNode ->
                    val project = collector.mavenProjects.getValue(projectNode.artifact.identifier())
                    processProjectDependencies(graphBuilder, project, projectNode.children, excludes)
                    project
                }.toList()
            }
        }

        buildLog.delete()

        val rootProject = collector.mavenProjects.values.find { it.file == definitionFile }
            ?: throw TychoBuildException("Tycho root project could not be built.")

        val rootIssues = createRootIssues(exitCode, collector, resolvedProjects)

        graphBuilder.packages().createIssuesForAutoGeneratedPoms(descriptor.displayName, rootIssues)

        return resolvedProjects.map { mavenProject ->
            val projectId = mavenProject.identifier(projectType)
            val project = mavenProject.toOrtProject(
                projectId,
                mavenProject.file,
                mavenProject.file.parentFile,
                graphBuilder.scopesFor(projectId)
            )
            val issues = rootIssues.takeIf { mavenProject == rootProject }.orEmpty()
            ProjectAnalyzerResult(project, emptySet(), issues)
        }
    }

    override fun createPackageManagerResult(projectResults: ProjectResults): PackageManagerResult =
        PackageManagerResult(projectResults, graphBuilder.build(), graphBuilder.packages())

    /**
     * Create the [MavenCli] instance to trigger a build on the analyzed project. Register the given [collector] and
     * [tracker], so that they are invoked during the build.
     */
    internal fun createMavenCli(collector: TychoProjectsCollector, tracker: P2ArtifactTracker): MavenCli =
        object : MavenCli(ClassWorld("plexus.core", javaClass.classLoader)) {
            override fun customizeContainer(container: PlexusContainer) {
                container.addComponent(
                    collector,
                    AbstractMavenLifecycleParticipant::class.java,
                    "TychoProjectsCollector"
                )

                P2ArtifactTracker.install(tracker, container)
            }
        }

    /**
     * Create the [DependencyGraphBuilder] for constructing the dependency graph of the analyzed Tycho project using
     * the given [mavenSupport], the encountered [mavenProjects], and the given [tracker] and [repositoryHelper].
     */
    internal fun createGraphBuilder(
        mavenSupport: MavenSupport,
        mavenProjects: Map<String, MavenProject>,
        tracker: P2ArtifactTracker,
        repositoryHelper: LocalRepositoryHelper
    ): DependencyGraphBuilder<DependencyNode> {
        val resolverFun =
            tychoPackageResolverFun(mavenSupport.defaultPackageResolverFun(), repositoryHelper, tracker)
        val dependencyHandler = MavenDependencyHandler(descriptor.id, projectType, mavenProjects, resolverFun)
        return DependencyGraphBuilder(dependencyHandler)
    }

    /**
     * Run a Maven build on the Tycho project in [projectRoot] utilizing the given [collector] and [tracker]. Return a
     * pair with the exit code of the Maven project and a [File] that contains the output generated during the build.
     */
    private fun runBuild(
        analysisRoot: File,
        collector: TychoProjectsCollector,
        tracker: P2ArtifactTracker,
        projectRoot: File,
        excludes: Excludes,
        skipExcluded: Boolean
    ): Pair<Int, File> {
        // The Maven CLI seems to change the context class loader. This has side effects on ORT's plugin mechanism.
        // To prevent this, store the class loader and restore it at the end of this function.
        val tccl = Thread.currentThread().contextClassLoader

        try {
            val buildLog = createOrtTempFile()

            val cli = createMavenCli(collector, tracker)

            // With the current CLI API, there does not seem to be another way to set the build root folder than
            // using a system property.
            System.setProperty(MavenCli.MULTIMODULE_PROJECT_DIRECTORY, projectRoot.absolutePath)

            val exitCode = cli.doMain(
                generateMavenOptions(analysisRoot, projectRoot, buildLog, excludes, skipExcluded),
                projectRoot.path,
                null,
                null
            ).also { logger.info { "Tycho analysis completed. Exit code: $it." } }

            return exitCode to buildLog
        } finally {
            Thread.currentThread().contextClassLoader = tccl
        }
    }

    /**
     * Create a [MavenSupport] instance to be used for resolving the packages found during the Maven build. Obtain
     * the local projects from the given [collector]
     */
    private fun createMavenSupport(collector: TychoProjectsCollector): MavenSupport {
        val localProjects = collector.mavenProjects
        val resolveFunc: (String) -> File? = { projectId -> localProjects[projectId]?.file }

        return MavenSupport(LocalProjectWorkspaceReader(resolveFunc))
    }

    /**
     * Process the [dependencies] of the given [project] by adding them to the [graphBuilder].
     */
    private fun processProjectDependencies(
        graphBuilder: DependencyGraphBuilder<DependencyNode>,
        project: MavenProject,
        dependencies: Collection<DependencyNode>,
        excludes: Excludes
    ) {
        val projectId = project.identifier(projectType)

        dependencies.filterNot { excludes.isScopeExcluded(it.dependency.scope) }.forEach { node ->
            graphBuilder.addDependency(DependencyGraph.qualifyScope(projectId, node.dependency.scope), node)
        }
    }

    /**
     * Create a list with [Issue]s for global build problems. Since this implementation executes a single
     * multi-module build, it is typically not possible to assign single issues to specific projects. Therefore, all
     * issues are assigned to the root project. In order to generate the issues, evaluate the [exitCode] of the Maven
     * build, the projects found by the [collector], and the [resolvedProjects] for which dependency information was
     * found.
     */
    private fun createRootIssues(
        exitCode: Int,
        collector: TychoProjectsCollector,
        resolvedProjects: List<MavenProject>
    ): MutableList<Issue> {
        val rootIssues = mutableListOf<Issue>()
        if (exitCode != 0) {
            rootIssues += createAndLogIssue(
                descriptor.displayName,
                "Maven build failed with non-zero exit code $exitCode."
            )
        }

        val missingProjects = collector.mavenProjects.keys - resolvedProjects.mapTo(mutableSetOf()) { it.internalId }

        missingProjects.forEach { projectId ->
            val coordinates = collector.mavenProjects.getValue(projectId).identifier(projectType).toCoordinates()
            rootIssues += createAndLogIssue(
                descriptor.displayName,
                "No dependency information found for project '$coordinates'. " +
                    "This may be caused by a build failure."
            )
        }

        return rootIssues
    }

    /**
     * Generate the command line options to be passed to the Maven CLI for the given [analysisRoot] and [root] folders
     * and the [dependencyTreeFile].
     */
    private fun generateMavenOptions(
        analysisRoot: File,
        root: File,
        dependencyTreeFile: File,
        excludes: Excludes,
        skipExcluded: Boolean
    ): Array<String> =
        buildList {
            // The "package" goal is required; otherwise the Tycho extension is not activated.
            add("package")
            add(DEPENDENCY_TREE_GOAL)
            add("-DoutputType=json")
            add("-DoutputFile=${dependencyTreeFile.absolutePath}")
            add("-DappendOutput=true")
            add("-Dverbose=true")

            generateModuleExcludes(analysisRoot, root, excludes, skipExcluded)?.takeUnless { it.isEmpty() }
                ?.let { excludedModules ->
                    add("-pl")
                    add(excludedModules)
                }
        }.toTypedArray()

    /**
     * Generate a list of submodules to be excluded for the Maven build with the given [rootProject] folder based on
     * the configured exclusions. The resulting string (if any) is used as the value of Maven's `-pl` option.
     */
    private fun generateModuleExcludes(
        analysisRoot: File,
        rootProject: File,
        excludes: Excludes,
        skipExcluded: Boolean
    ): String? {
        if (!skipExcluded) return null

        val analysisRootPath = analysisRoot.toPath()
        val rootProjectPath = rootProject.toPath()
        return rootProject.walk().filter { it.name == "pom.xml" }
            .map { it.toPath().parent }
            .filter { excludes.isPathExcluded(analysisRootPath.relativeSubPath(it)) }
            .joinToString(",") { "!${rootProjectPath.relativeSubPath(it)}" }
    }
}

/**
 * The version of the Maven dependency plugin to use. It is necessary to explicitly specify a version to prevent
 * that during a build, depending on the configured repositories, an outdated version is applied. The version
 * specified here does not necessarily need to be the most recent one; it is sufficient that this version supports
 * the functionality used by the Tycho implementation.
 */
private const val DEPENDENCY_PLUGIN_VERSION = "3.8.1"

/** The goal to invoke the Maven Dependency Plugin to generate a dependency tree. */
private const val DEPENDENCY_TREE_GOAL =
    "org.apache.maven.plugins:maven-dependency-plugin:$DEPENDENCY_PLUGIN_VERSION:tree"

/**
 * Return the relative path of [other] to this [Path].
 */
private fun Path.relativeSubPath(other: Path): String = relativize(other).invariantSeparatorsPathString

/**
 * A special exception class to indicate that a Tycho build failed completely.
 */
class TychoBuildException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * An internal helper class that gets registered as a Maven lifecycle participant to obtain all [MavenProject]s
 * encountered during the build.
 */
internal class TychoProjectsCollector : AbstractMavenLifecycleParticipant() {
    /**
     * Stores the projects that have been found during the Maven build. To be on the safe side with regard to
     * possible threading issues, use an [AtomicReference] to ensure safe publication.
     */
    private val projects = AtomicReference<Map<String, MavenProject>>(emptyMap())

    /**
     * Return the projects that have been found during the Maven build.
     */
    val mavenProjects: Map<String, MavenProject>
        get() = projects.get()

    override fun afterSessionEnd(session: MavenSession) {
        val builtProjects = session.projects.associateBy(MavenProject::internalId)
        projects.set(builtProjects)

        logger.info { "Found ${builtProjects.size} projects during build." }
    }
}

/**
 * Return a [PackageResolverFun] that can handle special OSGi artifacts detected during a Tycho analysis.
 * OSGi bundles retrieved from a P2 repository cannot be handled by the default resolver function, which is not aware
 * of the special repository layout. Also, they do not have a POM with metadata, but may contain some metadata in
 * their OSGi manifest.
 * This function first delegates to the given [delegate] function to handle normal packages in the usual way. If this
 * fails, it checks whether the dependency can be found in the p2 cache of the local Maven repository managed by
 * [repositoryHelper]. (Since the Tycho project has been built, the cache should be populated.) If this is the case,
 * it tries to obtain metadata from the OSGi manifest of the bundle. This approach is partly inspired by
 * https://github.com/OpenNTF/p2-layout-provider. Additional metadata for creating [RemoteArtifact] objects is
 * obtained from the given [tracker].
 */
internal fun tychoPackageResolverFun(
    delegate: PackageResolverFun,
    repositoryHelper: LocalRepositoryHelper,
    tracker: P2ArtifactTracker
): PackageResolverFun =
    { dependency ->
        runCatching {
            delegate(dependency)
        }.recoverCatching { exception ->
            createPackageFromLocalArtifact(dependency.artifact, repositoryHelper, tracker)
                ?: throw exception
        }.getOrThrow()
    }

/**
 * Create a [Package] for the given [artifact] obtaining metadata from the given [manifest] and [tracker]. This
 * function is used for OSGi artifacts for which no POM is available.
 */
internal fun createPackageFromManifest(artifact: Artifact, manifest: Manifest, tracker: P2ArtifactTracker): Package =
    with(manifest.mainAttributes) {
        val declaredLicenses = setOfNotNull(getValue("Bundle-License"))
        val declaredLicensesProcessed = processDeclaredLicenses(declaredLicenses)
        val authors = setOfNotNull(getValue("Bundle-Vendor"))
        val homepageUrl = getValue("Bundle-DocURL").orEmpty()

        val scmProperties = extractScmProperties(getValue("Eclipse-SourceReferences"))
        val vcs = parseScm(scmProperties.toScm(), artifact.identifier(), scmProperties[ScmProperties.PATH].orEmpty())
        val vcsProcessed = PackageManager.processPackageVcs(vcs, homepageUrl)

        Package(
            id = Identifier(
                type = "Maven",
                namespace = artifact.groupId,
                name = artifact.artifactId,
                version = artifact.version
            ),
            authors = authors,
            declaredLicenses = declaredLicenses,
            declaredLicensesProcessed = declaredLicensesProcessed,
            description = getValue("Bundle-Description").orEmpty(),
            homepageUrl = homepageUrl,
            binaryArtifact = tracker.getBinaryArtifactFor(artifact),
            sourceArtifact = tracker.getSourceArtifactFor(artifact),
            vcs = vcs,
            vcsProcessed = vcsProcessed
        )
    }

/**
 * Try to find an OSGi artifact in the local Maven repository managed by [repositoryHelper] that matches the given
 * [artifact]. If found, create a [Package] from the artifact's OSGi manifest using the given [tracker].
 */
private fun createPackageFromLocalArtifact(
    artifact: Artifact,
    repositoryHelper: LocalRepositoryHelper,
    tracker: P2ArtifactTracker
): Package? = repositoryHelper.osgiManifest(artifact)?.let { createPackageFromManifest(artifact, it, tracker) }

/**
 * An enumeration that defines the properties to be extracted from a source reference manifest header.
 */
private enum class ScmProperties {
    /** The connection URL. */
    CONNECTION,

    /** The tag in the source code management system. */
    TAG,

    /** The path of this component in a monorepo. */
    PATH
}

/**
 * Parse the given [scmInfo] string with a source references manifest header to a map of [ScmProperties].
 * See https://wiki.eclipse.org/PDE/UI/SourceReferences.
 */
private fun extractScmProperties(scmInfo: String?): Map<ScmProperties, String> =
    scmInfo?.split(',')?.firstOrNull()?.let { firstScmInfo ->
        buildMap {
            val fields = firstScmInfo.split(';')
            put(ScmProperties.CONNECTION, fields.first())

            fields.drop(1).mapNotNull { field ->
                field.split('=').takeIf { it.size == 2 }
            }.forEach { (key, value) ->
                runCatching { // Ignore unknown keys.
                    put(ScmProperties.valueOf(key.uppercase()), value.removeSurrounding("\""))
                }
            }
        }
    }.orEmpty()

/**
 * Convert this [Map] of [ScmProperties] to a [Scm] structure. Return *null* if mandatory properties are missing.
 */
private fun Map<ScmProperties, String>.toScm(): Scm? =
    get(ScmProperties.CONNECTION)?.let { connection ->
        Scm().apply {
            this.connection = connection
            tag = get(ScmProperties.TAG)
        }
    }
