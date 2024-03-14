package de.itemis.mps.gradle.migrate

import de.itemis.mps.gradle.logging.detectLogging
import jetbrains.mps.ide.ThreadUtils
import jetbrains.mps.lang.migration.runtime.base.MigrationModuleUtil
import jetbrains.mps.lang.migration.runtime.base.MigrationScript
import jetbrains.mps.lang.migration.runtime.base.MigrationScriptReference
import jetbrains.mps.migration.global.BaseProjectMigration
import jetbrains.mps.migration.global.ProjectMigrationsRegistry
import jetbrains.mps.project.Project
import jetbrains.mps.smodel.ModelAccessHelper
import jetbrains.mps.smodel.SLanguageHierarchy
import jetbrains.mps.smodel.SModelInternal
import jetbrains.mps.smodel.language.LanguageRegistry
import jetbrains.mps.util.Computable
import org.jetbrains.mps.openapi.language.SLanguage
import org.jetbrains.mps.openapi.module.SModule
import java.io.File

val logging = detectLogging()
val logger = logging.getLogger("de.itemis.mps.gradle.migrate")

fun migrate(args: MigrateArgs) {
    val loader = args.buildLoader()
    val moduleMigrationsToExclude = args.excludeModuleMigrations.toSet()
    val projectMigrationsToExclude = args.excludeProjectMigrations.toSet()

    loader.execute { environment ->
        for (projectDir in args.projects) {
            val project = environment.openProject(File(projectDir))
            try {
                runProjectMigrations(project, projectMigrationsToExclude)
                runRerunnableModuleMigrations(project, moduleMigrationsToExclude)
            } finally {
                environment.closeProject(project)
            }
        }
    }
}

fun runProjectMigrations(project: Project, migrationsToExclude: Set<ProjectMigration>) {
    @Suppress("DEPRECATION")
    val projectName = project.name
    logger.info("Executing all project migrations on $projectName on EDT")
    ThreadUtils.runInUIThreadAndWait {
        project.modelAccess.runWriteAction {
            for (migration in ProjectMigrationsRegistry.getInstance().migrations) {
                if (migration is BaseProjectMigration && ProjectMigration(migration.migrationId) in migrationsToExclude) {
                    logger.info("Not executing excluded project migration ${migration.migrationId}")
                    continue
                }

                logger.info("Executing project migration '${migration.description}' on $projectName")
                migration.execute(project)
            }
        }
    }
    logger.info("Done executing all project migrations on $projectName")
}

private fun runRerunnableModuleMigrations(
    project: Project,
    migrationsToExclude: Set<ModuleMigration>
) {
    val modules = ModelAccessHelper(project.modelAccess).runReadAction(Computable {
        MigrationModuleUtil.getMigrateableModulesFromProject(project)
    })
    for (module in modules) {
        logger.info("Executing re-runnable migrations on ${module.moduleName}")
        val languageRegistry = project.getComponent(LanguageRegistry::class.java)
        project.modelAccess.runWriteAction {
            val languages = SLanguageHierarchy(languageRegistry, module.usedLanguages).extended
            for (language in languages) {
                @Suppress("DEPRECATION")
                for (ver in 0 until language.languageVersion) {
                    val reference = MigrationScriptReference(language, ver)
                    val script: MigrationScript? = reference.resolve(project, true)
                    if (script != null && !script.requiresData().any() && script.isRerunnable) {
                        if (migrationsToExclude.contains(ModuleMigration(language.qualifiedName, ver))) {
                            logger.info("Not executing excluded $reference on ${module.moduleName}")
                            continue
                        }

                        logger.info("Executing $reference on ${module.moduleName}")
                        script.execute(module)
                        updateModelVersionsIfPossible(module, language, ver, ver + 1)
                    }
                }
            }
        }
        logger.info("Done executing re-runnable migrations on ${module.moduleName}")
    }
}

fun updateModelVersionsIfPossible(module: SModule, language: SLanguage, from: Int, to: Int) {
    val models = module.models
    for (model in models) {
        if (model !is SModelInternal) continue

        if (model.isReadOnly) continue // Ignore stubs

        if (model.getLanguageImportVersion(language) == from) {
            model.setLanguageImportVersion(language, to)
        }
    }
}