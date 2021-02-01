package ca.cutterslade.gradle.analyze

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.SourceSet

import java.util.concurrent.ConcurrentHashMap

class AnalyzeDependenciesPlugin implements Plugin<Project> {
  @Override
  void apply(final Project project) {
    if (project.rootProject == project) {
      project.rootProject.extensions.add(ProjectDependencyResolver.CACHE_NAME, new ConcurrentHashMap<>())
    }
    project.plugins.withId('java') {
      def commonTask = project.task('analyzeDependencies',
          group: 'Verification',
          description: 'Analyze project for dependency issues.'
      )

      project.tasks.check.dependsOn commonTask

      project.sourceSets.all { SourceSet sourceSet ->
        def unusedDeclared = project.configurations.create(sourceSet.getTaskName('permit', 'unusedDeclared')) {
          canBeConsumed = false
          canBeResolved = true
        }
        def usedUndeclared = project.configurations.create(sourceSet.getTaskName('permit', 'usedUndeclared')) {
          canBeConsumed = false
          canBeResolved = true
        }
        def aggregatorUsed = project.configurations.create(sourceSet.getTaskName('permit', 'aggregatorUse')) {
          canBeConsumed = false
          canBeResolved = true
          attributes {
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, Usage.JAVA_API))
          }
        }
        def apiHelper = project.configurations.create(sourceSet.getTaskName('apiHelper', '')) {
          canBeConsumed = false
          canBeResolved = true
        }

        def analyzeTask = project.task(sourceSet.getTaskName('analyze', 'classesDependencies'),
            dependsOn: sourceSet.classesTaskName, // needed for pre-4.0, later versions infer this from classesDirs
            type: AnalyzeDependenciesTask,
            group: 'Verification',
            description: "Analyze project for dependency issues related to ${sourceSet.name} source set.") {
          require = [
              project.configurations.getByName(sourceSet.compileClasspathConfigurationName)
          ]
          apiHelperConfiguration = apiHelper
          apiConfigurationName = sourceSet.apiConfigurationName
          allowedAggregatorsToUse = [
              aggregatorUsed
          ]
          allowedToUse = [
              usedUndeclared
          ]
          if (sourceSet.name == 'test')
            allowedToUse.add(project.configurations.compileClasspath)
          allowedToDeclare = [
              unusedDeclared
          ]
          def output = sourceSet.output
          // classesDirs was defined in gradle 4.0
          classesDirs = output.hasProperty('classesDirs') ? output.classesDirs : project.files(output.classesDir)
        }
        commonTask.dependsOn analyzeTask
      }
    }
  }
}
