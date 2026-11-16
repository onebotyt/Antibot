package com.example.gemini.data.harness.agy

import com.example.gemini.data.harness.model.AgyEffort
import com.example.gemini.data.harness.model.AgyMode
import com.example.gemini.data.harness.model.HarnessConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream-json protocol is the only contract with the CLI, so it is pinned here
 * with real lines from `agy --output-format stream-json`.
 */
class AgyCliProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses the init event`() {
        val line = """{"event":"init","conversation_id":"c3b66b04-872b-4fbe-a3a4-058a026ef20a","init":{"cwd":"/root/workspace","tools":["ask_permission","run_command","write_to_file"],"permission_mode":"request-review"}}"""
        val event = AgyCliProtocol.parseLine(line) as AgyStreamEvent.Init
        assertEquals("c3b66b04-872b-4fbe-a3a4-058a026ef20a", event.conversationId)
        assertEquals("/root/workspace", event.cwd)
        assertEquals(listOf("ask_permission", "run_command", "write_to_file"), event.tools)
        assertEquals("request-review", event.permissionMode)
        assertNull(event.model)
    }

    @Test
    fun `parses a streaming agent_response step`() {
        val line = """{"event":"step_update","step_update":{"conversation_id":"abc","step_index":2,"state":"ACTIVE","step_type":"agent_response","text_delta":"apple"}}"""
        val step = (AgyCliProtocol.parseLine(line) as AgyStreamEvent.Step).step
        assertEquals(2, step.stepIndex)
        assertEquals(AgyStepState.ACTIVE, step.state)
        assertEquals(AgyStepType.AGENT_RESPONSE, step.type)
        assertEquals("apple", step.textDelta)
        assertNull(step.tool)
    }

    @Test
    fun `parses a tool step with its call and output`() {
        val line = """{"event":"step_update","step_update":{"conversation_id":"edb1","step_index":4,"state":"DONE","step_type":"tool","tool_name":"run_command","duration_seconds":0.07,"tool_info":{"name":"run_command","parameters":{"CommandLine":"echo hello_headless_demo"},"output":"hello_headless_demo\r\n"}}}"""
        val step = (AgyCliProtocol.parseLine(line) as AgyStreamEvent.Step).step
        assertEquals(AgyStepType.TOOL, step.type)
        assertEquals("run_command", step.toolName)
        assertEquals(0.07, step.durationSeconds!!, 0.0001)
        val tool = step.tool!!
        assertEquals("run_command", tool.name)
        assertEquals("hello_headless_demo", tool.output?.trim())
        assertTrue(tool.parameters.toString().contains("hello_headless_demo"))
        assertTrue(!tool.isError)
    }

    @Test
    fun `parses a failed tool step`() {
        val line = """{"event":"step_update","step_update":{"conversation_id":"abc","step_index":5,"state":"DONE","step_type":"tool","tool_name":"write_to_file","tool_info":{"name":"write_to_file","error":{"type":"PERMISSION_DENIED","message":"needs approval"}}}}"""
        val tool = (AgyCliProtocol.parseLine(line) as AgyStreamEvent.Step).step.tool!!
        assertTrue(tool.isError)
        assertEquals("PERMISSION_DENIED", tool.errorType)
        assertEquals("needs approval", tool.errorMessage)
    }

    @Test
    fun `parses a successful result with usage`() {
        val line = """{"event":"result","result":{"conversation_id":"abc","status":"SUCCESS","response":"apple\n","duration_seconds":1.42,"num_turns":1,"usage":{"input_tokens":30384,"output_tokens":4,"thinking_tokens":0,"cache_read_tokens":0,"total_tokens":30388}}}"""
        val result = (AgyCliProtocol.parseLine(line) as AgyStreamEvent.Result).result
        assertEquals(AgyResultStatus.SUCCESS, result.status)
        assertEquals("apple\n", result.response)
        assertEquals(1, result.numTurns)
        assertEquals(30388L, result.usage?.totalTokens)
        assertTrue(!result.status.isFailure)
    }

    @Test
    fun `parses an error result`() {
        val line = """{"event":"result","result":{"conversation_id":"","status":"ERROR","response":"","error":"invalid model selection (--model \"nope\")","duration_seconds":0,"num_turns":0,"usage":{"input_tokens":0,"output_tokens":0,"thinking_tokens":0,"cache_read_tokens":0,"total_tokens":0}}}"""
        val result = (AgyCliProtocol.parseLine(line) as AgyStreamEvent.Result).result
        assertEquals(AgyResultStatus.ERROR, result.status)
        assertTrue(result.status.isFailure)
        assertTrue(result.error!!.startsWith("invalid model selection"))
    }

    @Test
    fun `skips logs, warnings and blank lines`() {
        assertNull(AgyCliProtocol.parseLine(""))
        assertNull(AgyCliProtocol.parseLine("   "))
        assertNull(AgyCliProtocol.parseLine("ERROR: logging before google.Init: I1116 05:27:30.389699 1 server_zerog.go:46] Initialized Android local timezone"))
        assertNull(AgyCliProtocol.parseLine("warning: ignoring unsupported stream input message event \"future_thing\""))
        assertNull(AgyCliProtocol.parseLine("not json at all"))
    }

    @Test
    fun `keeps unknown events instead of failing`() {
        val event = AgyCliProtocol.parseLine("""{"event":"future_thing","payload":{"a":1}}""") as AgyStreamEvent.Unknown
        assertEquals("future_thing", event.event)
    }

    @Test
    fun `user message is one NDJSON line the CLI accepts`() {
        val line = AgyCliProtocol.userMessageJson("Reply with \"apple\"\nthanks")
        assertTrue(!line.contains("\n"))
        val root = json.parseToJsonElement(line).jsonObject
        assertEquals("user", root["event"]!!.jsonPrimitive.content)
        val content = root["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("Reply with \"apple\"\nthanks", content)
    }

    @Test
    fun `buildArgs asks for a duplex stream-json session`() {
        val args = AgyCliProtocol.buildArgs(HarnessConfig(model = "gemini-4-carbon-high", effort = AgyEffort.XHIGH, mode = AgyMode.PLAN))
        assertTrue(args.containsAll(listOf("--input-format", "stream-json", "--output-format", "stream-json")))
        assertEquals("gemini-4-carbon-high", args[args.indexOf("--model") + 1])
        assertEquals("xhigh", args[args.indexOf("--effort") + 1])
        assertEquals("plan", args[args.indexOf("--mode") + 1])
        assertTrue(!args.contains("--conversation"))
        assertTrue(!args.contains("--dangerously-skip-permissions"))
    }

    @Test
    fun `buildArgs resumes a conversation and honours permissions`() {
        val args = AgyCliProtocol.buildArgs(
            HarnessConfig(skipPermissions = true, sandbox = true),
            resumeConversationId = "c3b66b04"
        )
        assertEquals("c3b66b04", args[args.indexOf("--conversation") + 1])
        assertTrue(args.contains("--dangerously-skip-permissions"))
        assertTrue(args.contains("--sandbox"))
        assertTrue(!args.contains("--model"))
    }
}
