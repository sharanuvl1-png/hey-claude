package com.personal.heyclaude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
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

    /** Searches Sharan's own Dropbox documents by name and content. */
    fun searchDropbox(query: String): String = "Dropbox documents aren't linked."

    /** One Dropbox file to read: a PDF or picture is passed to Claude whole, text files as text. */
    fun readDropboxFile(path: String): FileForClaude = FileForClaude("Dropbox documents aren't linked.")

    /** Every WhatsApp and app message saved in the last [days] days, optionally from one [app]. */
    fun recentMessages(days: Int, app: String): String = "No messages are saved on this phone."

    /** True when the work OneDrive is linked, so its tools are offered. */
    val hasWorkFiles: Boolean get() = false

    /** Searches Sharan's work OneDrive (read-only). Only for work questions. */
    fun searchWorkOneDrive(query: String): String = "Work OneDrive isn't linked in Mehitha."

    /** One work OneDrive file to read, by the id and name search_work_onedrive gave. */
    fun readWorkOneDriveFile(id: String, name: String): FileForClaude = FileForClaude("Work OneDrive isn't linked in Mehitha.")
}

/**
 * A file for Claude to read: [note] always, plus the PDF, the picture, or its [pages] as JPEG pictures.
 * [snipId] names the saved page pictures the app can cut a snippet from (see Snips).
 */
class FileForClaude(val note: String, val name: String = "", val pdf: ByteArray? = null,
                    val image: ByteArray? = null, val imageType: String = "image/jpeg",
                    val pages: List<ByteArray> = emptyList(), val snipId: String? = null)

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

    /**
     * Starts the conversation from an earlier chat's turns (true = Sharan's), so follow-ups keep
     * their context. Turns from the same side are joined, and it starts with Sharan and ends with Mehitha.
     */
    fun seed(turns: List<Pair<Boolean, String>>) {
        if (history.isNotEmpty()) return
        val merged = mutableListOf<Pair<Boolean, String>>()
        turns.filter { it.second.isNotBlank() }.takeLast(20).forEach { t ->
            if (merged.isNotEmpty() && merged.last().first == t.first) merged[merged.lastIndex] = t.first to merged.last().second + "\n\n" + t.second
            else merged += t
        }
        while (merged.isNotEmpty() && !merged.first().first) merged.removeAt(0)
        while (merged.isNotEmpty() && merged.last().first) merged.removeAt(merged.lastIndex)
        merged.forEach { (mine, text) ->
            history += if (mine) MessageParam.ofUser(text)
                       else MessageParam.ofAssistant(text)
        }
    }

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
                "messages captured from notifications, screens saved from apps Sharan chose (such as Zerodha holdings or " +
                "Blinkit orders, with the time they were seen), and the list of files and photos on the phone. " +
                "Use for questions about messages, people, bookings, payments, documents or files on the phone.",
            "query", "A few keywords, e.g. a name, company, amount or file name"))
        .addTool(Tool.builder().name("get_recent_messages")
            .description("Every WhatsApp and other app message saved on Sharan's phone in the last few days (from notifications), " +
                "oldest first. Use this, not search_phone_data, for questions like \"WhatsApp messages from the last 5 days\" " +
                "or \"anything I need to know from my chats this week\", then pick out what matters.")
            .inputSchema(Tool.InputSchema.builder()
                .properties(Tool.InputSchema.Properties.builder()
                    .putAdditionalProperty("days", JsonValue.from(mapOf("type" to "integer", "description" to "How many days back, 1 to 14")))
                    .putAdditionalProperty("app", JsonValue.from(mapOf("type" to "string", "description" to "Optional app name such as WhatsApp or Gmail; leave empty for all apps")))
                    .build())
                .required(listOf("days"))
                .build())
            .build())
        .addTool(tool("get_stock_scan",
            "Today's swing-trading stock scan: watchlist verdicts, entry, stop-loss, targets, and top picks " +
                "from Nifty 50, Midcap 150 and Smallcap 100. Use for any question about stocks or the market scan.",
            null, null))
        .addTool(tool("get_stock_chart",
            "The newest technical chart for one NSE stock: the chart picture link and the written analysis " +
                "(trend, levels, entry, stop-loss, targets, verdict). Use when Sharan asks about a stock's chart or setup.",
            "stock", "Company name or NSE symbol, e.g. PC Jeweller or PCJEWELLER"))
        .addTool(tool("search_dropbox",
            "Search Sharan's own Dropbox (documents, ID copies, bills, policies, statements, photos) by file name and " +
                "content. Returns each file's name, path, folder, date and an open link. Use for any document Sharan looks for, then " +
                "read_dropbox_file to look inside the most likely one when the answer needs a date or detail from it; " +
                "show each file Sharan should open on its own line as \"OPEN: <file name> | <link>\" so the app shows a button.",
            "query", "A few words from the file name or its contents, e.g. car insurance or PAN card"))
        .addTool(tool("read_dropbox_file",
            "Open one file from Sharan's Dropbox, by the path search_dropbox gave, and read it: PDFs (policies, " +
                "statements, bills) and photos or scans are read in full. Use it to find dates, amounts or details inside a document.",
            "path", "The file's path from search_dropbox, e.g. /Documents/Insurance/Policy.pdf"))
        .apply { if (tools.hasWorkFiles) {
            addTool(tool("search_work_onedrive",
                "Search Sharan's work OneDrive (office documents) by file name and content. Only for work questions; " +
                    "never use work files for personal expenses or notes. Returns each file's name, id, folder, date and open link; " +
                    "show files to open as \"OPEN: <file name> | <link>\".",
                "query", "A few words from the file name or its contents, e.g. Q3 roadmap or SOW Acme"))
            addTool(Tool.builder().name("read_work_onedrive_file")
                .description("Open one file from Sharan's work OneDrive, by the id and name search_work_onedrive gave, and read it: " +
                    "PDFs, Word, Excel and PowerPoint files (as PDF pages), pictures and text files.")
                .inputSchema(Tool.InputSchema.builder()
                    .properties(Tool.InputSchema.Properties.builder()
                        .putAdditionalProperty("id", JsonValue.from(mapOf("type" to "string", "description" to "The file's id from search_work_onedrive")))
                        .putAdditionalProperty("name", JsonValue.from(mapOf("type" to "string", "description" to "The file's name from search_work_onedrive")))
                        .build())
                    .required(listOf("id", "name"))
                    .build())
                .build())
        } }
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
            if (use.name() == "read_dropbox_file") stringArg(use._input(), "path").let { path ->
                return@map fileResult(use.id(), path) { tools.readDropboxFile(path) }
            }
            if (use.name() == "read_work_onedrive_file") stringArg(use._input(), "name").let { name ->
                return@map fileResult(use.id(), name) { tools.readWorkOneDriveFile(stringArg(use._input(), "id"), name) }
            }
            val (text, failed) = try {
                when (use.name()) {
                    "search_phone_data" -> tools.searchPhoneData(stringArg(use._input(), "query")) to false
                    "get_stock_scan" -> tools.stockScan() to false
                    "get_stock_chart" -> tools.stockChart(stringArg(use._input(), "stock")) to false
                    "get_recent_messages" -> tools.recentMessages(
                        ((use._input().convert(Map::class.java)?.get("days"))?.toString()?.toDoubleOrNull()?.toInt() ?: 3).coerceIn(1, 14),
                        stringArg(use._input(), "app")) to false
                    "search_dropbox" -> tools.searchDropbox(stringArg(use._input(), "query")) to false
                    "search_work_onedrive" -> tools.searchWorkOneDrive(stringArg(use._input(), "query")) to false
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

    /** The file as a tool result: its note, then the PDF as a document or the picture as an image. */
    private fun fileResult(id: String, path: String, read: () -> FileForClaude): ContentBlockParam {
        val file = try { read() } catch (e: Exception) {
            FileForClaude("Could not open $path: ${e.message ?: e.javaClass.simpleName}")
        }
        val blocks = mutableListOf(ToolResultBlockParam.Content.Block.ofText(file.note.take(MAX_TOOL_CHARS)))
        file.pdf?.let { blocks += ToolResultBlockParam.Content.Block.ofDocument(DocumentBlockParam.builder()
            .base64Source(java.util.Base64.getEncoder().encodeToString(it)).title(file.name.ifBlank { path }).build()) }
        file.image?.let { blocks += image(it, file.imageType) }
        file.pages.forEachIndexed { i, page ->
            blocks += ToolResultBlockParam.Content.Block.ofText("Page ${i + 1}:")
            blocks += image(page, "image/jpeg")
        }
        file.snipId?.let { id ->
            blocks += ToolResultBlockParam.Content.Block.ofText(
                if (unlocked || !Snips.isPrivate(file.name)) snipHint(id)
                else { Snips.drop(id); "Don't add a SNIP line for this file: it may hold confidential details." })
        }
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(id).contentOfBlocks(blocks).build())
    }

    private fun image(bytes: ByteArray, type: String) = ToolResultBlockParam.Content.Block.ofImage(Base64ImageSource.builder()
        .data(java.util.Base64.getEncoder().encodeToString(bytes)).mediaType(Base64ImageSource.MediaType.of(type)).build())

    private fun stringArg(input: JsonValue, key: String): String =
        (input.convert(Map::class.java)?.get(key) as? String).orEmpty()

    private fun textOf(response: Message): String =
        response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()

    companion object {
        /** Tells Claude how to ask the app for a cut-out of the page that holds the answer. */
        fun snipHint(id: String) = "Snippet id $id. When your answer comes from this file, add a line " +
            "\"SNIP: $id | page <n> | <top>-<bottom>\" where top and bottom are where the answer sits on that page, " +
            "as fractions of the page height from 0 (top) to 1 (bottom), e.g. \"SNIP: $id | page 1 | 0.42-0.50\". " +
            "The app shows that part of the page as a picture. At most 2 SNIP lines; keep the band tight around the answer."
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
            You are Mehitha, Sharan's personal assistant in India. Answers are read on a phone screen
            and may be read aloud.
            Start with one line beginning "SPOKEN:" that gives a casual one or two sentence gist to say
            out loud, with amounts said as words such as "2,500 rupees".
            Then the answer, laid out so it can be read at a glance:
            - First line: the answer itself in one short sentence.
            - Then, only if needed, short points, each on its own line starting with "• ", at most 15 words each.
            - When there are several topics, put a short label on its own line ending with ":" (for example
              "Bills:") with its points under it, and a blank line between topics. At most 4 topics, 4 points each.
            - No long paragraphs, no markdown such as ** or #, no tables, no links except IMAGE and OPEN lines (below).
            - Amounts as ₹ with Indian grouping, rounded: ₹3,598, ₹1.2 lakh, ₹2.5 crore.
            - Leave out detail Sharan didn't ask for. Keep the whole answer under 90 words unless Sharan asks for more.
            Sharan swing trades (holding days to a few weeks). Whenever a stock comes up, look at it as a swing
            trade: trend, entry zone, stop-loss, T1 and T2 and the usual time to T1 when the scan gives them, and
            what would cancel the setup. Use only real prices with their date; never give a level you didn't get from a tool or the scan.
            Use the tools to look things up. Never make up facts, numbers, prices or dates: if a tool
            returns nothing or fails, say plainly that you couldn't find it and what Sharan could give you.
            Never place orders, payments or trades, and never send messages on Sharan's behalf.
            Never read out OTPs or passwords.
            When a picture would help, such as a stock chart from get_stock_chart or an image link you
            found, put its direct https link alone on its own line as "IMAGE: <link>" and the app shows it.
            Work files (search_work_onedrive) are only for work questions; never mix office data into
            Sharan's personal expenses or notes.
            When you read a document with read_dropbox_file or read_work_onedrive_file, give the answer in words and add the SNIP line it
            describes so Sharan sees the matching part of the page without opening the file.
            To give Sharan a file or page to open, put "OPEN: <short name> | <https link>" on its own line and the app shows a button.
            Only use links you actually got from a tool, never guessed ones.
            You can set reminders: when Sharan asks to be reminded of something, work out the date and
            time from the current time below and add a line exactly like
            "REMIND: YYYY-MM-DD HH:MM | what to remind" (24-hour time; 09:00 if no time is given).
            The app turns that line into a notification, so confirm the reminder in one short sentence.
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
