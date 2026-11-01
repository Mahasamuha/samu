package com.mahasamuha.assistant.sms

import com.mahasamuha.assistant.contacts.ContactDirectory
import com.mahasamuha.assistant.contacts.Recipient
import com.mahasamuha.assistant.llm.ToolAnnotations
import com.mahasamuha.assistant.llm.ToolDefinition
import com.mahasamuha.assistant.tools.ActionTool
import com.mahasamuha.assistant.tools.Confirmation
import com.mahasamuha.assistant.tools.PendingAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * `send_sms`: always confirmed (plan §2). The read-back names the recipient from the phone's own
 * lookup of the number, and the text goes to that contact's saved number, so it says who will actually
 * get the text, whatever the model believes. [contacts] is null when contacts permission is off; the
 * read-back then gives the number. [send] sends the text; [SmsSender.send] is the real one.
 */
class SendSmsTool(
    private val send: suspend (number: String, message: String) -> SmsSender.Outcome,
    private val contacts: ContactDirectory?,
) : ActionTool {
    override val definition =
        ToolDefinition(
            name = "send_sms",
            description =
                "Send a text message from the user's phone. The phone reads it back to the user and sends " +
                    "it only after they confirm.",
            inputSchema =
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "phone_number",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "The recipient's number, from find_contact or as the user said it."),
                            ).put(
                                "message",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "The text to send, worded as the user would write it."),
                            ),
                    ).put("required", JSONArray().put("phone_number").put("message"))
                    .put("additionalProperties", false),
            // Sending adds a message rather than changing or deleting anything, so not destructive; the
            // confirmation comes from this tool's Ask, not from the hint.
            annotations = ToolAnnotations(title = "Send text", readOnlyHint = false, destructiveHint = false, openWorldHint = true),
        )

    override suspend fun prepare(arguments: JSONObject): PendingAction {
        val number = arguments.optString("phone_number").trim()
        // Control and invisible format characters aren't spoken, so they'd be sent without being heard.
        val message = arguments.optString("message").filterNot { it != '\n' && Character.getType(it).toByte() in UNSPOKEN }.trim()
        require(
            ContactDirectory.isPhoneNumber(number, minDigits = 3),
        ) { "A plain phone number is required: digits only, no codes or letters" }
        require(message.isNotEmpty()) { "The message is empty" }
        require(message.length <= MAX_LENGTH) { "The message is too long to read back; keep it under $MAX_LENGTH characters" }

        val recipient = contacts?.recipient(number) ?: Recipient.of(number)
        // The added period only paces the speech; the text is sent as written.
        val body = if (message.last() in ".!?") message else "$message."
        return PendingAction(Confirmation.Ask("Text ${recipient.description}: $body Send it?")) {
            when (send(recipient.number, message)) {
                SmsSender.Outcome.Sent -> JSONObject().put("status", "sent")
                SmsSender.Outcome.Pending -> JSONObject().put("status", "sending").put("note", "The phone hasn't confirmed it was sent yet")
            }.toString()
        }
    }

    private companion object {
        const val MAX_LENGTH = 320
        val UNSPOKEN = setOf(Character.CONTROL, Character.FORMAT)
    }
}
