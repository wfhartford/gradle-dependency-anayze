package ca.cutterslade.gradle.analyze

import groovy.transform.CompileStatic
import org.apache.maven.artifact.Artifact
import org.apache.maven.shared.dependency.analyzer.ClassAnalyzer
import org.apache.maven.shared.dependency.analyzer.DefaultClassAnalyzer
import org.apache.maven.shared.dependency.analyzer.DependencyAnalyzer
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis
import org.apache.maven.shared.dependency.analyzer.asm.ASMDependencyAnalyzer
import org.gradle.api.Project
import org.gradle.api.UnknownDomainObjectException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.logging.Logger

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@CompileStatic
class ProjectDependencyResolver {
    static final String CACHE_NAME = 'ca.cutterslade.gradle.analyze.ProjectDependencyResolver.artifactClassCache'

    private final ClassAnalyzer classAnalyzer = new DefaultClassAnalyzer()
    private final DependencyAnalyzer dependencyAnalyzer = new ASMDependencyAnalyzer()

    private final ConcurrentHashMap<File, Set<String>> artifactClassCache
    private final Logger logger
    private final List<Configuration> require
    private final List<Configuration> api
    private final List<Configuration> allowedToUse
    private final List<Configuration> allowedToDeclare
    private final Iterable<File> classesDirs
    private final Map<ResolvedArtifact, Set<ResolvedArtifact>> aggregatorsWithDependencies
    private final List<Configuration> allowedAggregatorsToUse
    private final boolean logDependencyInformationToFile
    private final Path buildDirPath

    ProjectDependencyResolver(final Project project,
                              final List<Configuration> require,
                              final Configuration apiHelperConfiguration,
                              final String apiConfigurationName,
                              final List<Configuration> allowedToUse,
                              final List<Configuration> allowedToDeclare,
                              final Iterable<File> classesDirs,
                              final List<Configuration> allowedAggregatorsToUse,
                              final boolean logDependencyInformationToFile) {
        try {
            this.artifactClassCache =
                    project.rootProject.extensions.getByName(CACHE_NAME) as ConcurrentHashMap<File, Set<String>>
        }
        catch (UnknownDomainObjectException e) {
            throw new IllegalStateException('Dependency analysis plugin must also be applied to the root project', e)
        }
        this.logger = project.logger
        this.require = removeNulls(require) as List
        this.api = configureApiHelperConfiguration(apiHelperConfiguration, project, apiConfigurationName)
        this.allowedAggregatorsToUse = removeNulls(allowedAggregatorsToUse) as List
        this.allowedToUse = removeNulls(allowedToUse) as List
        this.allowedToDeclare = removeNulls(allowedToDeclare) as List
        this.classesDirs = classesDirs
        this.aggregatorsWithDependencies = getAggregatorsMapping()
        this.logDependencyInformationToFile = logDependencyInformationToFile
        this.buildDirPath = project.buildDir.toPath()
    }

    static <T> Collection<T> removeNulls(final Collection<T> collection) {
        if (null == collection) {
            []
        } else {
            collection.removeAll { it == null }
            collection
        }
    }

    ProjectDependencyAnalysis analyzeDependencies() {
        Set<ResolvedDependency> allowedToUseDeps = allowedToUseDependencies

        Set<ResolvedDependency> allowedToDeclareDeps = allowedToDeclareDependencies

        Set<ResolvedDependency> requiredDeps = requiredDependencies
        requiredDeps.removeAll { req ->
            allowedToUseDeps.any { allowed ->
                req.module.id == allowed.module.id
            }
        }

        Set<File> dependencyArtifacts = findModuleArtifactFiles(requiredDeps)

        Set<File> allDependencyArtifacts = findAllModuleArtifactFiles(requiredDeps)

        Map<File, Set<String>> fileClassMap = buildArtifactClassMap(allDependencyArtifacts)

        Set<String> dependencyClasses = analyzeClassDependencies()

        Set<File> usedArtifacts = buildUsedArtifacts(fileClassMap, dependencyClasses)

        Set<File> usedDeclaredArtifacts = new LinkedHashSet<File>(dependencyArtifacts)
        usedDeclaredArtifacts.retainAll(usedArtifacts)

        Set<File> usedUndeclaredArtifacts = new LinkedHashSet<File>(usedArtifacts)
        usedUndeclaredArtifacts.removeAll(dependencyArtifacts)

        Set<File> unusedDeclaredArtifacts = new LinkedHashSet<File>(dependencyArtifacts)
        unusedDeclaredArtifacts.removeAll(usedArtifacts)

        Set<ResolvedArtifact> allowedToUseArtifacts = allowedToUseDeps*.moduleArtifacts?.flatten() as Set<ResolvedArtifact>

        Set<ResolvedArtifact> allowedToDeclareArtifacts = allowedToDeclareDeps*.moduleArtifacts?.
                flatten() as Set<ResolvedArtifact>

        Set<ResolvedArtifact> allArtifacts = resolveArtifacts(require)

        if (logDependencyInformationToFile) {
            final def outputDirectoryPath = buildDirPath.resolve(AnalyzeDependenciesTask.DEPENDENCY_ANALYZE_DEPENDENCY_DIRECTORY_NAME)
            Files.createDirectories(outputDirectoryPath)
            final Path analyzeOutputPath = outputDirectoryPath.resolve("analyzeDependencies.log")
            new PrintWriter(Files.newOutputStream(analyzeOutputPath)).withCloseable { final analyzeWriter ->
                analyzeWriter.println('dependencyArtifacts:')
                dependencyArtifacts.forEach({ final artifact -> analyzeWriter.println(artifact) })
                analyzeWriter.println()

                analyzeWriter.println("allDependencyArtifacts:")
                allDependencyArtifacts.forEach({ final artifact -> analyzeWriter.println(artifact) })
                analyzeWriter.println()

                analyzeWriter.println("fileClassMap:")
                for (final def classMapEntry : fileClassMap) {
                    analyzeWriter.print("${classMapEntry.key}=")
                    for (final def theClass : classMapEntry.value) {
                        analyzeWriter.print(theClass)
                        analyzeWriter.print(', ')
                    }
                    analyzeWriter.println()
                }
                analyzeWriter.println()

                analyzeWriter.println("dependencyClasses:")
                dependencyClasses.forEach({ final dependencyClass -> analyzeWriter.println(dependencyClass) })
                analyzeWriter.println()

                analyzeWriter.println("usedArtifacts:")
                usedArtifacts.forEach({ final usedArtifact -> analyzeWriter.println(usedArtifact) })
                analyzeWriter.println()

                analyzeWriter.println("usedDeclaredArtifacts:")
                usedDeclaredArtifacts.forEach({ final usedDeclaredArtifact -> analyzeWriter.println(usedDeclaredArtifact) })
                analyzeWriter.println()

                analyzeWriter.println("usedUndeclaredArtifacts:")
                usedUndeclaredArtifacts.forEach({ final usedUndeclared -> analyzeWriter.println(usedUndeclared) })
                analyzeWriter.println()

                analyzeWriter.println("unusedDeclaredArtifacts:")
                unusedDeclaredArtifacts.forEach({ final unusedDeclared -> analyzeWriter.println(unusedDeclared) })
                analyzeWriter.println()

                analyzeWriter.println("allowedToUseArtifacts:")
                allowedToUseArtifacts.forEach({ final allowedToUse -> analyzeWriter.println(allowedToUse) })
                analyzeWriter.println()

                analyzeWriter.println("allowedToDeclareArtifacts:")
                allowedToDeclareArtifacts.forEach({ final allowedToDeclare -> analyzeWriter.println(allowedToDeclare) })
                analyzeWriter.println()

                analyzeWriter.println("allArtifacts:")
                allArtifacts.forEach({ final artifact -> analyzeWriter.println(artifact) })
                analyzeWriter.println()
            }
        } else {
            logger.info "dependencyArtifacts = $dependencyArtifacts"
            logger.info "allDependencyArtifacts = $allDependencyArtifacts"
            logger.info "fileClassMap = $fileClassMap"
            logger.info "dependencyClasses = $dependencyClasses"
            logger.info "usedArtifacts = $usedArtifacts"
            logger.info "usedDeclaredArtifacts = $usedDeclaredArtifacts"
            logger.info "usedUndeclaredArtifacts = $usedUndeclaredArtifacts"
            logger.info "unusedDeclaredArtifacts = $unusedDeclaredArtifacts"
            logger.info "allowedToUseArtifacts = $allowedToUseArtifacts"
            logger.info "allowedToDeclareArtifacts = $allowedToDeclareArtifacts"
            logger.info "allArtifacts = $allArtifacts"
        }

        def usedDeclared = allArtifacts.findAll { ResolvedArtifact artifact -> artifact.file in usedDeclaredArtifacts }
        def usedUndeclared = allArtifacts.findAll { ResolvedArtifact artifact -> artifact.file in usedUndeclaredArtifacts }
        if (allowedToUseArtifacts) {
            def allowedToUseComponentIdentifiers = allowedToUseArtifacts.collect { it.id.componentIdentifier }
            usedUndeclared.removeAll { allowedToUseComponentIdentifiers.contains(it.id.componentIdentifier) }
        }
        def unusedDeclared = allArtifacts.findAll { ResolvedArtifact artifact -> artifact.file in unusedDeclaredArtifacts }
        if (allowedToDeclareArtifacts) {
            def allowedToDeclareComponentIdentifiers = allowedToDeclareArtifacts.collect { it.id.componentIdentifier }
            unusedDeclared.removeAll { allowedToDeclareComponentIdentifiers.contains(it.id.componentIdentifier) }
        }

        if (!aggregatorsWithDependencies.isEmpty()) {
            def usedIdentifiers = (requiredDependencies.collect { it.allModuleArtifacts }.flatten() as Set<ResolvedArtifact>)
                    .collect { it.id }
                    .collect { it.componentIdentifier }
            def aggregatorUsage = used(usedIdentifiers, usedArtifacts).groupBy { it.value.isEmpty() }
            if (aggregatorUsage.containsKey(true)) {
                def unusedAggregatorArtifacts = aggregatorUsage.get(true).keySet() as Set<ResolvedArtifact>
                unusedDeclared += unusedAggregatorArtifacts.intersect(requiredDeps.collect { it.allModuleArtifacts }.flatten() as Set<ResolvedArtifact>)
            }
            if (aggregatorUsage.containsKey(false)) {
                def usedAggregator = aggregatorUsage.get(false)
                def usedAggregatorDependencies = usedAggregator.keySet()
                usedDeclared += usedAggregatorDependencies.intersect(unusedDeclared, { ResolvedArtifact a, ResolvedArtifact b ->
                    a.id.componentIdentifier == b.id.componentIdentifier ? 0 : a.id.componentIdentifier.displayName <=> b.id.componentIdentifier.displayName
                } as Comparator<ResolvedArtifact>)

                def flatten = usedAggregator.values().flatten().collect({ it -> (ResolvedArtifact) it })
                unusedDeclared += usedDeclared.intersect(flatten)
                def usedAggregatorComponentIdentifiers = usedAggregatorDependencies.collect { it.id.componentIdentifier } as Set<ResolvedArtifact>
                unusedDeclared.removeAll { usedAggregatorComponentIdentifiers.contains(it.id.componentIdentifier) }
                def apiComponentIdentifiers = (getFirstLevelDependencies(api).collect { it.allModuleArtifacts }.flatten() as Set<ResolvedArtifact>)
                        .collect { it.id.componentIdentifier } as Set<ComponentIdentifier>
                unusedDeclared.removeAll { apiComponentIdentifiers.contains(it.id.componentIdentifier) }

                usedUndeclared -= usedAggregatorDependencies.collect { aggregatorsWithDependencies.get(it) }.flatten()
                def usedDeclaredComponentIdentifiers = usedDeclared.collect { it.id.componentIdentifier } as Set<ResolvedArtifact>
                usedUndeclared += usedAggregatorDependencies.findAll { !usedDeclaredComponentIdentifiers.contains(it.id.componentIdentifier) }
                usedUndeclared.removeIf { allowedToUseArtifacts.contains(it) && aggregatorsWithDependencies.keySet().contains(it) }
            }
        }

        return new ProjectDependencyAnalysis(
                usedDeclared.unique { it.file } as Set<Artifact>,
                usedUndeclared.unique { it.file } as Set<Artifact>,
                unusedDeclared.unique { it.file } as Set<Artifact>)
    }

    private Set<ResolvedDependency> getRequiredDependencies() {
        getFirstLevelDependencies(require)
    }

    private Set<ResolvedDependency> getAllowedToUseDependencies() {
        getFirstLevelDependencies(allowedToUse)
    }

    private Set<ResolvedDependency> getAllowedToDeclareDependencies() {
        getFirstLevelDependencies(allowedToDeclare)
    }

    static Set<ResolvedDependency> getFirstLevelDependencies(final List<Configuration> configurations) {
        configurations.collect { it.resolvedConfiguration.firstLevelModuleDependencies }.flatten() as Set<ResolvedDependency>
    }

    /**
     * Map each of the files declared on all configurations of the project to a collection of the class names they
     * contain.
     * @param project the project we're working on
     * @return a Map of files to their classes
     * @throws IOException
     */
    private Map<File, Set<String>> buildArtifactClassMap(Set<File> dependencyArtifacts) throws IOException {
        final Map<File, Set<String>> artifactClassMap = [:]

        int hits = 0
        int misses = 0
        dependencyArtifacts.each { File file ->
            def classes = artifactClassCache[file]
            if (null == classes) {
                logger.debug "Artifact class cache miss for $file"
                misses++
                classes = classAnalyzer.analyze(file.toURI().toURL()).asImmutable()
                artifactClassCache.putIfAbsent(file, classes)
            } else {
                logger.debug "Artifact class cache hit for $file"
                hits++
            }
            artifactClassMap.put(file, classes)
        }
        logger.info "Built artifact class map with $hits hits and $misses misses; cache size is ${artifactClassCache.size()}"
        return artifactClassMap
    }

    private Set<File> findModuleArtifactFiles(Set<ResolvedDependency> dependencies) {
        ((dependencies
                .collect { it.moduleArtifacts }.flatten()) as Set<ResolvedArtifact>)
                .collect { it.file }.unique() as Set<File>
    }

    private Set<File> findAllModuleArtifactFiles(Set<ResolvedDependency> dependencies) {
        ((dependencies
                .collect { it.allModuleArtifacts }.flatten()) as Set<ResolvedArtifact>)
                .collect { it.file }.unique() as Set<File>
    }

    /**
     * Find and analyze all class files to determine which external classes are used.
     * @param project
     * @return a Set of class names
     */
    private Set<String> analyzeClassDependencies() {
        classesDirs.collect { File it -> dependencyAnalyzer.analyze(it.toURI().toURL()) }
                .flatten() as Set<String>
    }

    /**
     * Determine which of the project dependencies are used.
     *
     * @param artifactClassMap a map of Files to the classes they contain
     * @param dependencyClasses all classes used directly by the project
     * @return a set of project dependencies confirmed to be used by the project
     */
    private Set<File> buildUsedArtifacts(Map<File, Set<String>> artifactClassMap, Set<String> dependencyClasses) {
        Set<File> usedArtifacts = new HashSet()

        dependencyClasses.each { String className ->
            File artifact = artifactClassMap.find { it.value.contains(className) }?.key
            if (artifact) {
                usedArtifacts << artifact
            }
        }
        return usedArtifacts
    }

    private Set<ResolvedArtifact> resolveArtifacts(List<Configuration> configurations) {
        Set<ResolvedArtifact> allArtifacts = (((configurations
                .collect { it.resolvedConfiguration }
                .collect { it.firstLevelModuleDependencies }.flatten()) as Set<ResolvedDependency>)
                .collect { it.allModuleArtifacts }.flatten()) as Set<ResolvedArtifact>
        allArtifacts
    }

    private List<Configuration> configureApiHelperConfiguration(Configuration apiHelperConfiguration, Project project, String apiConfigurationName) {
        final def apiConfiguration = [project.configurations.findByName(apiConfigurationName)]
        apiHelperConfiguration.extendsFrom(removeNulls(apiConfiguration) as Configuration[])
        [apiHelperConfiguration]
    }

    private Map<ResolvedArtifact, Set<ResolvedArtifact>> getAggregatorsMapping() {
        if (!allowedAggregatorsToUse.empty) {
            def resolvedArtifacts = resolveArtifacts(allowedAggregatorsToUse).collectEntries { [it.moduleVersion.toString(), it] }
            def dependencies = getFirstLevelDependencies(allowedAggregatorsToUse)
            dependencies.collectEntries({ it ->
                resolvedArtifacts.containsKey(it.name) ? [resolvedArtifacts.get(it.name), it.allModuleArtifacts as Set<ResolvedArtifact>] : [:]
            })
        } else {
            [:]
        }
    }

    private Map<ResolvedArtifact, Collection<ResolvedArtifact>> used(List<ComponentIdentifier> allDependencyArtifacts, Set<File> usedArtifacts) {
        def usedAggregators = new LinkedHashMap<ResolvedArtifact, Collection<ResolvedArtifact>>()

        aggregatorsWithDependencies.each {
            if (allDependencyArtifacts.contains(it.key.id.componentIdentifier)) {
                def filesForAggregator = it.value.collect({ it.file })
                def disjoint = filesForAggregator.intersect(usedArtifacts)
                usedAggregators.put(it.key, it.value.findAll { disjoint.contains(it.file) })
            }
        }

        removeDuplicates(usedAggregators)
    }

    private Map<ResolvedArtifact, Collection<ResolvedArtifact>> removeDuplicates(Map<ResolvedArtifact, Collection<ResolvedArtifact>> usedAggregators) {
        def aggregatorsSortedByDependencies = usedAggregators.sort { l, r ->
            l.value.size() <=> r.value.size() ?: aggregatorsWithDependencies.get(r.key).size() <=> aggregatorsWithDependencies.get(l.key).size()
        }

        def aggregatorArtifactAlreadySeen = [] as Set
        aggregatorsSortedByDependencies.removeAll {
            aggregatorArtifactAlreadySeen.add(it.key)
            aggregatorsSortedByDependencies.any { it2 -> !aggregatorArtifactAlreadySeen.contains(it2.key) && it2.value.containsAll(it.value) }
        }
        logger.debug "used aggregators: $aggregatorsSortedByDependencies.keySet()"
        return aggregatorsSortedByDependencies
    }
}
