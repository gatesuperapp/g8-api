package com.a4a.g8api.services

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import java.util.Properties

class EmailService(
    smtpHost: String = System.getenv("SMTP_HOST") ?: "127.0.0.1",
    smtpPort: String = System.getenv("SMTP_PORT") ?: "25",
    private val noOp: Boolean = System.getenv("EMAIL_NOOP") == "true",
) {

    private val log = LoggerFactory.getLogger(EmailService::class.java)

    private val session: Session = Session.getInstance(Properties().apply {
        put("mail.smtp.host", smtpHost)
        put("mail.smtp.port", smtpPort)
        put("mail.smtp.auth", "false")
        put("mail.smtp.starttls.enable", "false")
        put("mail.smtp.connectiontimeout", "10000")
        put("mail.smtp.timeout", "10000")
        put("mail.smtp.writetimeout", "10000")
    })

    companion object {
        private const val FROM_NAME = "G8 Invoicing"
        private const val FROM_EMAIL = "noreply@the-gate.fr"
    }

    fun sendEmail(to: String, subject: String, htmlBody: String): Boolean {
        if (noOp) return true
        return try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(FROM_EMAIL, FROM_NAME))
                setRecipient(Message.RecipientType.TO, InternetAddress(to))
                this.subject = subject
                setContent(htmlBody, "text/html; charset=UTF-8")
            }
            Transport.send(message)
            true
        } catch (e: Exception) {
            log.error("Failed to send email to $to", e)
            false
        }
    }

    /**
     * Send a magic-link email. The body and subject differ based on [purpose] to give
     * a clearer signal to the recipient: signing up for a new account vs. logging back in.
     * If [purpose] is unrecognized we default to the login template — safer wording.
     */
    fun sendMagicLinkEmail(to: String, token: String, purpose: String = "login"): Boolean {
        return when (purpose) {
            "signup" -> sendEmail(to, signupSubject(), signupBody(token))
            else -> sendEmail(to, loginSubject(), loginBody(token))
        }
    }

    private fun signupSubject() = "Bienvenue sur g8 — confirmez votre inscription"
    private fun loginSubject() = "Votre lien de connexion g8 (valide 15 minutes)"

    private fun signupBody(token: String) = wrapHtml(
        title = "Bienvenue sur g8",
        intro = "Votre inscription à g8 a été demandée avec cette adresse email.",
        ctaLabel = "Confirmer mon inscription",
        ctaUrl = webLink(token),
        ignoreNote = "Si vous n'êtes pas à l'origine de cette inscription, ignorez simplement ce message : aucun compte ne sera créé.",
    )

    private fun loginBody(token: String) = wrapHtml(
        title = "Connexion à g8",
        intro = "Voici votre lien pour vous connecter à g8 :",
        ctaLabel = "Se connecter à g8",
        ctaUrl = webLink(token),
        ignoreNote = "Si vous n'avez pas demandé cette connexion, ignorez ce message : personne ne peut accéder à votre compte sans ce lien.",
    )

    private fun webLink(token: String) = "https://api.the-gate.fr/auth?token=$token"

    private fun wrapHtml(
        title: String,
        intro: String,
        ctaLabel: String,
        ctaUrl: String,
        ignoreNote: String,
    ): String = """
        <!DOCTYPE html>
        <html>
        <head><meta charset="UTF-8"></head>
        <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
            <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                <h2 style="color: #932092;">$title</h2>
                <p>Bonjour,</p>
                <p>$intro</p>
                <p style="text-align: center; margin: 30px 0;">
                    <a href="$ctaUrl"
                       style="background-color: #932092; color: white; padding: 15px 30px;
                              text-decoration: none; border-radius: 25px; font-weight: bold;">
                        $ctaLabel
                    </a>
                </p>
                <p style="font-size: 14px; color: #666;">
                    Ce lien est valable 15 minutes et ne peut être utilisé qu'une seule fois.
                </p>
                <p style="font-size: 14px; color: #666;">$ignoreNote</p>
                <hr style="border: none; border-top: 1px solid #eee; margin: 30px 0;">
                <p style="font-size: 12px; color: #999;">
                    Une question ? Écrivez-nous : <a href="mailto:contact@the-gate.fr" style="color: #932092;">contact@the-gate.fr</a>
                </p>
            </div>
        </body>
        </html>
    """.trimIndent()
}
