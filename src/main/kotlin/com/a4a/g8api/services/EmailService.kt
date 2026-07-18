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
            // Hash the recipient so this error line does not leak raw addresses into
            // journald — AuthLogger already anonymises emails in security events, and
            // an SMTP failure record has no reason to be looser about PII.
            log.error("Failed to send email (recipient_hash=${hashEmailForLog(to)})", e)
            false
        }
    }

    /**
     * Send a magic-link email. The body and subject vary by [purpose] (signup vs. login
     * vs. post-checkout) and by [locale] (French if the locale tag starts with "fr",
     * English otherwise — including null/unknown). Two languages on purpose: anything
     * else falls back to English so a German user gets readable text rather than French.
     * If [purpose] is unrecognized we default to the login template — safer wording.
     */
    fun sendMagicLinkEmail(
        to: String,
        token: String,
        purpose: String = "login",
        locale: String? = null,
    ): Boolean {
        val lang = pickLang(locale)
        return when (purpose) {
            "signup" -> sendEmail(to, signupSubject(lang), signupBody(token, lang))
            "premium_signup" -> sendEmail(
                to, premiumSignupSubject(lang), premiumSignupBody(token, lang)
            )
            else -> sendEmail(to, loginSubject(lang), loginBody(token, lang))
        }
    }

    private fun signupSubject(l: Lang) = when (l) {
        Lang.FR -> "Confirmez votre inscription à g8"
        Lang.EN -> "Confirm your g8 sign-up"
    }
    private fun premiumSignupSubject(l: Lang) = when (l) {
        Lang.FR -> "Bienvenue chez les membres premium g8"
        Lang.EN -> "Welcome to g8 Premium"
    }
    private fun loginSubject(l: Lang) = when (l) {
        Lang.FR -> "Votre lien de connexion g8 (valide 15 minutes)"
        Lang.EN -> "Your g8 sign-in link (valid for 15 minutes)"
    }

    private fun signupBody(token: String, l: Lang) = when (l) {
        Lang.FR -> wrapHtml(
            lang = l,
            title = "Confirmez votre inscription à g8",
            bodyHtml = """
                <p>Une inscription à g8 a été demandée avec cette adresse e-mail.</p>
                <p>Pour débloquer toutes les fonctionnalités, vous pouvez devenir membre premium :
                   <a href="https://the-gate.fr/premium" style="color: #932092;">https://the-gate.fr/premium</a></p>
                <p>Pour activer votre compte, confirmez votre inscription ci-dessous :</p>
            """.trimIndent(),
            ctaLabel = "Confirmer mon inscription",
            ctaUrl = webLink(token),
            ignoreNote = "Si vous n'êtes pas à l'origine de cette inscription, ignorez simplement ce message : aucun compte ne sera créé.",
        )
        Lang.EN -> wrapHtml(
            lang = l,
            title = "Confirm your g8 sign-up",
            bodyHtml = """
                <p>A g8 sign-up has been requested with this email address.</p>
                <p>To unlock every feature, you can become a premium member:
                   <a href="https://the-gate.fr/premium" style="color: #932092;">https://the-gate.fr/premium</a></p>
                <p>To activate your account, confirm your sign-up below:</p>
            """.trimIndent(),
            ctaLabel = "Confirm my sign-up",
            ctaUrl = webLink(token),
            ignoreNote = "If you didn't ask to sign up, just ignore this message: no account will be created.",
        )
    }

    private fun premiumSignupBody(token: String, l: Lang) = when (l) {
        Lang.FR -> wrapHtml(
            lang = l,
            title = "Bienvenue chez les membres premium g8",
            bodyHtml = """
                <p>Merci pour votre abonnement g8 ! Votre compte premium est actif et toutes les fonctionnalités sont débloquées.</p>
                <p>Pour finaliser la création de votre compte et accéder à l'application, confirmez votre inscription ci-dessous :</p>
            """.trimIndent(),
            ctaLabel = "Accéder à mon compte",
            ctaUrl = webLink(token),
            ignoreNote = "Si vous n'êtes pas à l'origine de cet abonnement, écrivez-nous à contact@the-gate.fr.",
        )
        Lang.EN -> wrapHtml(
            lang = l,
            title = "Welcome to g8 Premium",
            bodyHtml = """
                <p>Thank you for your g8 subscription! Your premium account is active and every feature is unlocked.</p>
                <p>To finish setting up your account and access the app, confirm your sign-up below:</p>
            """.trimIndent(),
            ctaLabel = "Access my account",
            ctaUrl = webLink(token),
            ignoreNote = "If you didn't subscribe, please write to us at contact@the-gate.fr.",
        )
    }

    private fun loginBody(token: String, l: Lang) = when (l) {
        Lang.FR -> wrapHtml(
            lang = l,
            title = "Connexion à g8",
            bodyHtml = "<p>Voici votre lien pour vous connecter à g8 :</p>",
            ctaLabel = "Se connecter à g8",
            ctaUrl = webLink(token),
            ignoreNote = "Si vous n'avez pas demandé cette connexion, ignorez ce message : personne ne peut accéder à votre compte sans ce lien.",
        )
        Lang.EN -> wrapHtml(
            lang = l,
            title = "Sign in to g8",
            bodyHtml = "<p>Here is your link to sign in to g8:</p>",
            ctaLabel = "Sign in to g8",
            ctaUrl = webLink(token),
            ignoreNote = "If you didn't request this sign-in, ignore this message: nobody can access your account without this link.",
        )
    }

    private fun webLink(token: String) = "https://api.the-gate.fr/auth?token=$token"

    private fun wrapHtml(
        lang: Lang,
        title: String,
        bodyHtml: String,
        ctaLabel: String,
        ctaUrl: String,
        ignoreNote: String,
    ): String {
        val greeting = if (lang == Lang.FR) "Bonjour," else "Hello,"
        val validity = if (lang == Lang.FR) {
            "Ce lien est valable 15 minutes et ne peut être utilisé qu'une seule fois."
        } else {
            "This link is valid for 15 minutes and can only be used once."
        }
        val footerLead = if (lang == Lang.FR) "Une question ? Écrivez-nous : " else "Any questions? Write to us: "
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                    <h2 style="color: #932092;">$title</h2>
                    <p>$greeting</p>
                    $bodyHtml
                    <p style="text-align: center; margin: 30px 0;">
                        <a href="$ctaUrl"
                           style="background-color: #932092; color: white; padding: 15px 30px;
                                  text-decoration: none; border-radius: 25px; font-weight: bold;">
                            $ctaLabel
                        </a>
                    </p>
                    <p style="font-size: 14px; color: #666;">
                        $validity
                    </p>
                    <p style="font-size: 14px; color: #666;">$ignoreNote</p>
                    <hr style="border: none; border-top: 1px solid #eee; margin: 30px 0;">
                    <p style="font-size: 12px; color: #999;">
                        $footerLead<a href="mailto:contact@the-gate.fr" style="color: #932092;">contact@the-gate.fr</a>
                    </p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private enum class Lang { FR, EN }

    /**
     * Pick a language from a BCP-47 tag. Anything starting with "fr" is French (covers
     * "fr", "fr-FR", "fr-CA", …). Everything else — including null, "auto", "de", "en",
     * "en-US" — falls to English. We deliberately don't try to detect German (or others)
     * yet; falling back to English keeps the matrix tractable.
     */
    private fun pickLang(locale: String?): Lang =
        if (locale?.lowercase()?.startsWith("fr") == true) Lang.FR else Lang.EN
}
