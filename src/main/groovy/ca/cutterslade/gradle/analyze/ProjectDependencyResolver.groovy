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

import java.util.concurrent.ConcurrentHashMap

@CompileStatic
class ProjectDependencyResolver {
  static final String CACHE_NAME = 'ca.cutterslade.gradle.analyze.ProjectDependencyResolver.artifactClassCache'

  private final ClassAnalyzer classAnalyzer = new DefaultClassAnalyzer()
  private final DependencyAnalyzer dependencyAnalyzer = new ASMDependencyAnalyzer()

  private final ConcurrentHashMap<File, Set<String>> artifactClassCache
  private final Logger logger
  private final List<Configuration> require
  private final List<Configuration> allowedToUse
  private final List<Configuration> allowedToDeclare
  private final Iterable<File> classesDirs
  private final Map<ResolvedArtifact, Set<ResolvedArtifact>> aggregatorsWithDependencies
  private final List<Configuration> allowedAggregatorsToUse

  ProjectDependencyResolver(final Project project, final List<Configuration> require,
      final List<Configuration> allowedToUse, final List<Configuration> allowedToDeclare,
      final Iterable<File> classesDirs, final List<Configuration> allowedAggregatorsToUse) {
    try {
      this.artifactClassCache =
          project.rootProject.extensions.getByName(CACHE_NAME) as ConcurrentHashMap<File, Set<String>>
    }
    catch (UnknownDomainObjectException e) {
      throw new IllegalStateException('Dependency analysis plugin must also be applied to the root project', e)
    }
    this.logger = project.logger
    this.require = removeNulls(require) as List
    this.allowedAggregatorsToUse = removeNulls(allowedAggregatorsToUse) as List
    this.allowedToUse = removeNulls(allowedToUse) as List
    this.allowedToDeclare = removeNulls(allowedToDeclare) as List
    this.classesDirs = classesDirs
    this.aggregatorsWithDependencies = getAggregatorsMapping()
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
    Set<ResolvedDependency> requiredDeps = requiredDependencies - allowedToUseDeps
    Set<File> dependencyArtifacts = findModuleArtifactFiles(requiredDeps)
    logger.info "dependencyArtifacts = $dependencyArtifacts"

    Set<File> allDependencyArtifacts = findAllModuleArtifactFiles(requiredDeps)
    logger.info "allDependencyArtifacts = $allDependencyArtifacts"

    Map<File, Set<String>> fileClassMap = buildArtifactClassMap(allDependencyArtifacts)
    logger.info "fileClassMap = $fileClassMap"

    Set<String> dependencyClasses = analyzeClassDependencies()
    logger.info "dependencyClasses = $dependencyClasses"

    Set<File> usedArtifacts = buildUsedArtifacts(fileClassMap, dependencyClasses)
    logger.info "usedArtifacts = $usedArtifacts"

    Set<File> usedDeclaredArtifacts = new LinkedHashSet<File>(dependencyArtifacts)
    usedDeclaredArtifacts.retainAll(usedArtifacts)
    logger.info "usedDeclaredArtifacts = $usedDeclaredArtifacts"

    Set<File> usedUndeclaredArtifacts = new LinkedHashSet<File>(usedArtifacts)
    usedUndeclaredArtifacts.removeAll(dependencyArtifacts)
    logger.info "usedUndeclaredArtifacts = $usedUndeclaredArtifacts"

    Set<File> unusedDeclaredArtifacts = new LinkedHashSet<File>(dependencyArtifacts)
    unusedDeclaredArtifacts.removeAll(usedArtifacts)
    logger.info "unusedDeclaredArtifacts = $unusedDeclaredArtifacts"

    Set<ResolvedArtifact> allowedToUseArtifacts = allowedToUseDeps*.moduleArtifacts?.flatten() as Set<ResolvedArtifact>
    logger.info "allowedToUseArtifacts = $allowedToUseArtifacts"
    Set<ResolvedArtifact> allowedToDeclareArtifacts = allowedToDeclareDeps*.moduleArtifacts?.
        flatten() as Set<ResolvedArtifact>
    logger.info "allowedToDeclareArtifacts = $allowedToDeclareArtifacts"

    Set<ResolvedArtifact> allArtifacts = resolveArtifacts(require)
    logger.info "allArtifacts = $allArtifacts"

    def usedDeclared = allArtifacts.findAll { ResolvedArtifact artifact -> artifact.file in usedDeclaredArtifacts }
    def usedUndeclared = allArtifacts.findAll { ResolvedArtifact artifact -> artifact.file in usedUndeclaredArtifacts }
    if (allowedToUseArtifacts) {
      usedUndeclared -= allowedToUseArtifacts
    }
    def unusedDeclared = allArtifacts.findAll { ResolvedArtifact artifact -> artifact.file in unusedDeclaredArtifacts }
    if (allowedToDeclareArtifacts) {
      unusedDeclared -= allowedToDeclareArtifacts
    }

    if (!aggregatorsWithDependencies.isEmpty()) {
      def usedIdentifiers = (requiredDeps.collect {it.allModuleArtifacts}.flatten() as Set<ResolvedArtifact>)
          .collect {it.id}
          .collect {it.componentIdentifier}
      def aggregatorUsage = used(usedIdentifiers, usedArtifacts).groupBy { it.value.size() > 0 }
      if (aggregatorUsage.containsKey(false)) {
        unusedDeclared += aggregatorUsage.get(false).keySet()
      }
      if (aggregatorUsage.containsKey(true)) {
        def usedAggregator = aggregatorUsage.get(true)
        def usedAggregatorDependencies = usedAggregator.keySet()
        usedDeclared += usedAggregatorDependencies.intersect(unusedDeclared, { ResolvedArtifact a, ResolvedArtifact b ->
          a.id.componentIdentifier == b.id.componentIdentifier ? 0 : 1 } as Comparator<ResolvedArtifact>)

        def usedAggregatorComponentIdentifiers = usedAggregatorDependencies.collect { it.id.componentIdentifier } as Set<ResolvedArtifact>
        unusedDeclared.removeAll { usedAggregatorComponentIdentifiers.contains(it.id.componentIdentifier) }
        def flatten = usedAggregator.values().flatten().collect({ it -> (ResolvedArtifact) it })
        unusedDeclared += usedDeclared.intersect(flatten)

        usedUndeclared -= usedAggregatorDependencies.collect { aggregatorsWithDependencies.get(it) }.flatten()
        def usedDeclaredComponentIdentifiers = usedDeclared.collect { it.id.componentIdentifier } as Set<ResolvedArtifact>
        usedUndeclared += usedAggregatorDependencies.findAll { !usedDeclaredComponentIdentifiers.contains(it.id.componentIdentifier) }
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
