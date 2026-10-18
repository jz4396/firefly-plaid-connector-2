package net.djvk.fireflyPlaidConnector2.logging

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import net.djvk.fireflyPlaidConnector2.util.Redaction
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter

/**
 * Logback `%msg` replacement that redacts secrets. Registered in logback-spring.xml.
 */
class MaskingMessageConverter : MessageConverter() {
    override fun convert(event: ILoggingEvent): String = Redaction.mask(super.convert(event))
}

/**
 * Logback `%wEx` replacement that redacts secrets from exception messages and stack traces.
 */
class MaskingThrowableConverter : ExtendedWhitespaceThrowableProxyConverter() {
    override fun throwableProxyToString(tp: IThrowableProxy?): String = Redaction.mask(super.throwableProxyToString(tp))
}
