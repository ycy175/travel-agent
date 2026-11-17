package com.ycy.travelagent.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * 基于 MCP Java SDK 0.17.2 的 StdIO 航班查询 Server。
 *
 * <p>注意：StdIO 模式下 <b>stdout 是 JSON-RPC 协议通道</b>，
 * 所有日志必须写到 stderr，否则会把协议报文污染成非法 JSON。
 */
public class FlightMcpServer {

    public static void main(String[] args) throws Exception {
        // 0. StdIO 模式下 stdout 只能承载 JSON-RPC：
        //    先把 System.out 重定向到 stderr，日志框架初始化时会把 ConsoleAppender 绑定到 stderr；
        //    再把原始 stdout 单独交给 MCP transport，避免日志和协议报文混在同一个流里。
        PrintStream protocolOut = System.out;
        System.setOut(System.err);
        org.slf4j.LoggerFactory.getLogger(FlightMcpServer.class); // 强制日志框架在此刻完成初始化

        // 1. MCP SDK 0.17.x 使用 McpJsonMapper（没有 McpJsonDefaults，也不是 Jackson ObjectMapper）
        McpJsonMapper jsonMapper = McpJsonMapper.getDefault();
        var transportProvider = new StdioServerTransportProvider(jsonMapper, System.in, protocolOut);

        // 2. Tool 用无参 builder()，再 name()/description()/inputSchema()
        //    JSON Schema 是 record，没有 Builder，直接 new
        Map<String, Object> properties = Map.of(
                "from", Map.of("type", "string", "description", "出发城市"),
                "to", Map.of("type", "string", "description", "目的城市"),
                "date", Map.of("type", "string", "description", "出发日期 yyyy-MM-dd"));

        var inputSchema = new McpSchema.JsonSchema(
                "object",
                properties,
                List.of("from", "to", "date"),
                false,   // additionalProperties
                null,    // defs
                null);   // definitions

        var tool = McpSchema.Tool.builder()
                .name("search_flights")
                .description("查询指定日期的航班选项")
                .inputSchema(inputSchema)
                .build();

        // 3. SyncToolSpecification.builder() + callHandler(BiFunction<Exchange, CallToolRequest, CallToolResult>)
        var searchFlightsTool = McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> arguments = request.arguments();
                    if (arguments == null) {
                        arguments = Map.of();
                    }
                    String from = String.valueOf(arguments.get("from"));
                    String to = String.valueOf(arguments.get("to"));
                    String date = String.valueOf(arguments.get("date"));

                    // 必须用 stderr：stdout 被 MCP 协议占用
                    System.err.println(">>> [MCP Server] 收到航班查询请求: "
                            + from + " -> " + to + "，日期: " + date);

                    String resultText = "为您找到以下航班：\n"
                            + "1. 08:00 CA1234 经济舱 ¥1280\n"
                            + "2. 14:30 MU5678 经济舱 ¥1450";

                    return McpSchema.CallToolResult.builder()
                            .addTextContent(resultText)
                            .build();
                })
                .build();

        // 4. SyncSpecification: serverInfo(String, String) / capabilities(...) / tools(...) / build()
        var server = McpServer.sync(transportProvider)
                .serverInfo("flight-mcp-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(searchFlightsTool)
                .build();

        System.err.println(">>> [MCP Server] 航班查询 MCP Server 已启动（StdIO 模式）");

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        // 阻塞主线程，防止进程刚启动就退出（StdIO Server 没有 Web 容器来维持生命周期）
        Thread.currentThread().join();
    }
}
