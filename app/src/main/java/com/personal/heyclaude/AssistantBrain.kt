package com.personal.heyclaude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** What Mehitha can look up on its own, outside Claude. */
interface MehithaTools {
    /** Searches SMS, WhatsApp/app messages and the file list synced to Dropbox. */
    fun searchPhoneData(query: String): String

    /** Today's stock scan (watchlist and top picks) from the daily GitHub run. */
    fun stockScan(): String

    /** The newest technical chart for a stock: its picture link and the written analysis. */
    fun stockChart(stock: String): String = "No charts available."

    /** Sharan's Zerodha holdings and P&L, read-only. */
    fun portfolio(): String = "Zerodha isn't connected."
}

/**
 * Talks to Claude for the voice assistant: one conversation per session, with web
 * search plus Mehitha's own tools. Plain Kotlin so it can be tested off the phone.
 */
class AssistantBrain(
    apiKey: String,
    private val tools: MehithaTools,
    baseUrl: String? = null,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.of("Asia/Kolkata")) },
    private val model: String = MODEL,
) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    private val history = mutableListOf<MessageParam>()

    /** Set once Sharan has said the right passcode in this chat: confidential details may then be shared. */
    @Volatile var unlocked = false

    /** Answers one spoken question, running tools as needed. Returns plain text. */
    fun ask(question: String): String {
        history += MessageParam.ofUser(question)
        repeat(MAX_ROUNDS) {
            val response = client.messages().create(request())
            history += response.toParam()
            when (response.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> history += runTools(response)
                StopReason.PAUSE_TURN -> Unit // server tools still working: send again as is
                StopReason.REFUSAL -> return "Sorry, I can't help with that one."
                else -> return textOf(response).ifBlank { "Sorry, I didn't get an answer. Please ask again." }
            }
        }
        return "That took too many steps. Please ask in a simpler way."
    }

    private fun request(): MessageCreateParams = MessageCreateParams.builder()
        .model(model)
        .maxTokens(4000L)
        .system(SYSTEM + "\n" + (if (unlocked) UNLOCKED else LOCKED) + "\nCurrent date and time in India: " + now().format(STAMP) + ".")
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        .apply {
            // Haiku takes the basic web search; the newer one (with filtering) needs Sonnet or Opus.
            if (model.startsWith("claude-haiku")) addTool(WebSearchTool20250305.builder().maxUses(3L).build())
            else addTool(WebSearchTool20260209.builder().maxUses(3L).build())
        }
        .addTool(tool("search_phone_data",
            "Search Sharan's phone data synced to Dropbox: text messages (OTPs removed), WhatsApp and other app " +
                "messages captured from notifications, and the list of files and photos on the phone. " +
                "Use for questions about messages, people, bookings, payments, documents or files on the phone.",
            "query", "A few keywords, e.g. a name, company, amount or file name"))
        .addTool(tool("get_stock_scan",
            "Today's swing-trading stock scan: watchlist verdicts, entry, stop-loss, targets, and top picks " +
                "from Nifty 50, Midcap 150 and Smallcap 100. Use for any question about stocks or the market scan.",
            null, null))
        .addTool(tool("get_portfolio",
            "Sharan's Zerodha holdings: value, today's change, overall profit or loss and biggest movers. " +
                "Read-only. Use for questions about his own stocks, portfolio or P&L.",
            null, null))
        .addTool(tool("get_stock_chart",
            "The newest technical chart for one NSE stock: the chart picture link and the written analysis " +
                "(trend, levels, entry, stop-loss, targets, verdict). Use when Sharan asks about a stock's chart or setup.",
            "stock", "Company name or NSE symbol, e.g. PC Jeweller or PCJEWELLER"))
        .messages(history)
        .build()

    private fun tool(name: String, description: String, param: String?, paramDescription: String?): Tool {
        val schema = Tool.InputSchema.builder()
        if (param != null) {
            schema.properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty(param, JsonValue.from(mapOf("type" to "string", "description" to paramDescription)))
                .build())
            schema.required(listOf(param))
        } else {
            schema.properties(Tool.InputSchema.Properties.builder().build())
        }
        return Tool.builder().name(name).description(description).inputSchema(schema.build()).build()
    }

    private fun runTools(response: Message): MessageParam {
        val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
            val (text, failed) = try {
                when (use.name()) {
                    "search_phone_data" -> tools.searchPhoneData(stringArg(use._input(), "query")) to false
                    "get_stock_scan" -> tools.stockScan() to false
                    "get_portfolio" -> tools.portfolio() to false
                    "get_stock_chart" -> tools.stockChart(stringArg(use._input(), "stock")) to false
                    else -> "Unknown tool ${use.name()}" to true
                }
            } catch (e: Exception) {
                "Could not get this data: ${e.message ?: e.javaClass.simpleName}" to true
            }
            ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(use.id())
                .content(text.take(MAX_TOOL_CHARS))
                .isError(failed)
                .build())
        }
        return MessageParam.ofUserOfContentBlockParams(results)
    }

    private fun stringArg(input: JsonValue, key: String): String =
        (input.convert(Map::class.java)?.get(key) as? String).orEmpty()

    private fun textOf(response: Message): String =
        response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()

    companion object {
        const val MODEL = "claude-opus-5-5"

        /** The models Mehitha can answer with, fastest first: label to model id. */
        val MODELS = listOf(
            "Fast (Haiku 5.5)" to "claude-haiku-5-5",
            "Balanced (Sonnet 5.5)" to "claude-sonnet-5-5",
            "Best (Opus 5.5)" to "claude-opus-5-5",
        )
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        private const val MAX_ROUNDS = 8
        private const val MAX_TOOL_CHARS = 12_000
        private val STAMP = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a")

        private val SYSTEM = """
            You are Mehitha, Sharan's personal voice assistant in India. Your answer is shown on screen
            and may be read aloud, so write plain sentences with no markdown, tables, symbols or links (except IMAGE lines, below).
            Start with one line beginning "SPOKEN:" that gives a casual one or two sentence gist to say
            out loud, then the answer. Keep it under 120 words unless Sharan asks for detail.
            Use the tools to look things up. Never make up facts, numbers, prices or dates: if a tool
            returns nothing or fails, say plainly that you couldn't find it and what Sharan could give you.
            Never place orders, payments or trades, and never send messages on Sharan's behalf.
            Never read out OTPs or passwords.
            Say amounts in rupees as words a speaker would use, for example "2,500 rupees".
            When a picture would help, such as a stock chart from get_stock_chart or an image link you
            found, put its direct https link alone on its own line as "IMAGE: <link>" and the app shows it.
            Only use links you actually got from a tool, never guessed ones.
        """.trimIndent()

        private val LOCKED = """
            Confidential details need Sharan's passcode first: card, bank account, IFSC or UPI ID numbers,
            PAN, Aadhaar, salary or payslips, policy numbers, ID documents, tax returns, medical records
            and similar. If a question asks for any of these, do not reveal, hint at or summarise them:
            answer with exactly one line, PASSCODE_NEEDED, and nothing else. You may still say that a
            document exists. Never ask for, guess or mention what the passcode is.
        """.trimIndent()

        private val UNLOCKED = """
            Sharan has said the right passcode in this chat, so you may share confidential details
            (card, bank, PAN, Aadhaar, salary, policy numbers and similar) found with the tools.
        """.trimIndent()
    }
}
