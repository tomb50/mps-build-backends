package de.itemis.mps.gradle.logging

import org.apache.commons.logging.Log
import org.apache.commons.logging.impl.Jdk14Logger
import java.util.logging.*

internal class LoggingJul : Logging {
    val logger = Logger.getLogger("de.itemis.mps")

    override fun getLogger(name: String): Log = Jdk14Logger(name)

    override fun configure(level: LogLevel) {
        logger.level = level.toJul()

        if (logger.handlers.isEmpty()) {
            val handler = ConsoleHandler()
            handler.level = logger.level

            // IDEA Environment uses a special IdeaLogRecordFormatter that provides timestamps and we'd rather reuse it
            // to have consistent formatting and consistent timestamp reference point.
            //
            // Otherwise, use the default formatter.
            val formatter = getRootLoggerConsoleFormatter()
            if (formatter != null) {
                handler.formatter = formatter

                // Avoid duplicate log messages
                logger.useParentHandlers = false
            }
            logger.addHandler(handler)
        }
    }

    private fun LogLevel.toJul(): Level =
        when (this) {
            LogLevel.ALL -> Level.ALL
            LogLevel.INFO -> Level.INFO
            LogLevel.WARN -> Level.WARNING
            LogLevel.ERROR -> Level.SEVERE
            LogLevel.OFF -> Level.OFF
        }

    private fun getRootLoggerConsoleFormatter(): Formatter? =
        Logger.getLogger("").handlers.asSequence()
            .filterIsInstance(ConsoleHandler::class.java)
            .map { it.formatter }
            .singleOrNull()
}
