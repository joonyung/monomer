package cc.monomer.control.auth

import cc.monomer.identity.domain.model.User
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val INVITATION_SENDER_DISPLAY_NAME = "Monomer"

internal data class InvitationEmail(val subject: String, val text: String, val html: String)

private const val SUBJECT_NAME_LIMIT = 60
private const val PRODUCT_SUMMARY = "Monomer gives every agent on a team one shared foundation."
private val controlCharacters = Regex("[\\p{Cntrl}\\u0080-\\u009F\\u2028\\u2029\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]+")
private val expiryFormat = DateTimeFormatter.ofPattern("MMM d, yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

internal fun invitationEmail(deliveryEmail: String, organizationDisplayName: String, invitedBy: User?, expiresAt: Instant, acceptanceLink: String): InvitationEmail {
    val organization = singleLine(organizationDisplayName)
    val inviterName = invitedBy?.let { singleLine(it.displayName) }?.takeIf { it.isNotEmpty() }
    val inviterEmail = invitedBy?.let { singleLine(it.primaryEmail) }?.takeIf { it.isNotEmpty() && !it.equals(inviterName, ignoreCase = true) }
    val inviter = inviterName ?: inviterEmail
    val inviterLine = when {
        inviterName != null && inviterEmail != null -> "$inviterName ($inviterEmail)"
        else -> inviter ?: "An administrator"
    }
    val subject = if (inviter == null) "You're invited to join ${subjectName(organization)} on Monomer"
        else "${subjectName(inviter)} invited you to join ${subjectName(organization)} on Monomer"
    val expiry = expiryFormat.format(expiresAt)
    val invitationLine = "$inviterLine invited you to join $organization on Monomer."
    val signInLine = "Sign in with the Google account for $deliveryEmail to accept. Other accounts can't use this invitation."
    val expiryLine = "This invitation expires on $expiry."
    val ignoreLine = "If you weren't expecting this invitation, you can ignore this email. Nothing changes unless you accept."
    val footerLine = "Sent by Monomer on behalf of $organization."
    val text = listOf(invitationLine, PRODUCT_SUMMARY, "Accept the invitation:\n$acceptanceLink", signInLine, expiryLine, ignoreLine, "--\n$footerLine")
        .joinToString("\n\n", postfix = "\n")
    val html = """
        <!doctype html>
        <html lang="en">
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${escape(subject)}</title></head>
        <body style="margin:0;padding:0;background-color:#f4f4f5;">
        <div style="display:none;max-height:0;overflow:hidden;">${escape("Accept by $expiry to join $organization.")}</div>
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#f4f4f5;">
        <tr><td align="center" style="padding:32px 16px;">
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;background-color:#ffffff;border-radius:8px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;color:#18181b;">
        <tr><td style="padding:32px 32px 0;font-size:14px;font-weight:600;letter-spacing:0.02em;">Monomer</td></tr>
        <tr><td style="padding:24px 32px 0;font-size:22px;line-height:30px;font-weight:600;">Join ${escape(organization)} on Monomer</td></tr>
        <tr><td style="padding:16px 32px 0;font-size:15px;line-height:24px;">${escape(invitationLine)}</td></tr>
        <tr><td style="padding:8px 32px 0;font-size:15px;line-height:24px;color:#52525b;">${escape(PRODUCT_SUMMARY)}</td></tr>
        <tr><td style="padding:24px 32px 0;">
        <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr><td style="border-radius:6px;background-color:#18181b;">
        <a href="${escape(acceptanceLink)}" style="display:inline-block;padding:12px 20px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;">Accept invitation</a>
        </td></tr></table>
        </td></tr>
        <tr><td style="padding:24px 32px 0;font-size:14px;line-height:22px;">${escape(signInLine)}</td></tr>
        <tr><td style="padding:8px 32px 0;font-size:14px;line-height:22px;">${escape(expiryLine)}</td></tr>
        <tr><td style="padding:16px 32px 0;font-size:13px;line-height:20px;color:#52525b;">If the button doesn't work, copy this link into your browser:<br><a href="${escape(acceptanceLink)}" style="color:#18181b;word-break:break-all;">${escape(acceptanceLink)}</a></td></tr>
        <tr><td style="padding:16px 32px 24px;font-size:13px;line-height:20px;color:#52525b;">${escape(ignoreLine)}</td></tr>
        <tr><td style="padding:24px 32px 32px;font-size:12px;line-height:18px;color:#71717a;border-top:1px solid #e4e4e7;">${escape(footerLine)}</td></tr>
        </table>
        </td></tr>
        </table>
        </body>
        </html>
    """.trimIndent()
    return InvitationEmail(subject, text, html)
}

private fun singleLine(value: String): String = value.replace(controlCharacters, " ").trim()

private fun subjectName(value: String): String =
    if (value.codePointCount(0, value.length) <= SUBJECT_NAME_LIMIT) value
    else value.substring(0, value.offsetByCodePoints(0, SUBJECT_NAME_LIMIT - 1)).trimEnd() + "…"

private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
