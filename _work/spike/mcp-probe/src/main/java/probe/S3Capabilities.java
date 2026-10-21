package probe;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * spike S3：验证「不注册的能力，不会出现在 capabilities 里」。
 *
 * 对比两组：
 *   A) 不传 capabilities()  —— SDK 默认值
 *   B) 显式传 capabilities(tools=false 且不调用 logging())
 *
 * 另测：
 *   - 运行时 addTool / removeTool 是否可用（需构建期已开启 tools 能力）
 *   - addTool 是否会触发 notifyClients（对应 M19「不主动推」定案）
 */
public class S3Capabilities {

    /** 最小桩传输：不需要真实网络，只为让 McpServer 能 build 出来。 */
    static final class StubProvider implements McpServerTransportProvider {
        @Override
        public void setSessionFactory(McpServerSession.Factory sessionFactory) {
            // no-op
        }

        @Override
        public Mono<Void> notifyClients(String method, Object params) {
            System.out.println("    >>> [stub] notifyClients(" + method + ", " + params
                    + ")  <-- 被自动触发 = SDK 会主动推送，与 M19 定案冲突！");
            return Mono.empty();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }
    }

    static void dump(String label, McpSchema.ServerCapabilities c) {
        System.out.println("  " + label);
        System.out.println("    tools        = " + c.tools());
        System.out.println("    prompts      = " + c.prompts());
        System.out.println("    resources    = " + c.resources());
        System.out.println("    logging      = " + c.logging() + (c.logging() == null ? "   <-- 已关掉 ✅" : "   <-- 仍在声明 ⚠️"));
        System.out.println("    completions  = " + c.completions());
        System.out.println("    experimental = " + c.experimental());
        System.out.println("    extensions   = " + c.extensions());
    }

    static McpSchema.Tool dynamicTool() {
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("type", "string");
        city.put("minLength", 1);
        city.put("description", "城市名");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("city", city);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("city"));
        schema.put("additionalProperties", false);

        return McpSchema.Tool.builder("get_weather", schema)
                .title("查天气")
                .description("运行时构造的动态工具")
                .build();
    }

    public static void main(String[] args) {
        System.out.println("Java = " + System.getProperty("java.version"));
        System.out.println();

        McpSchema.Tool tool = dynamicTool();
        System.out.println("=== 0) 运行时构造的 Tool（inputSchema 由我们传入的 Map 决定） ===");
        System.out.println("  name        = " + tool.name());
        System.out.println("  inputSchema = " + tool.inputSchema());
        System.out.println();

        // ---------- A) 不传 capabilities：SDK 默认值 ----------
        System.out.println("########## A) 不传 capabilities() ##########");
        McpSyncServer defaultServer = McpServer.sync(new StubProvider())
                .serverInfo("probe-default", "0.1.0")
                .build();
        dump("默认 capabilities:", defaultServer.getAsyncServer().getServerCapabilities());
        System.out.println();

        // ---------- B) 显式传 capabilities：只开 tools，不碰 logging ----------
        System.out.println("########## B) 显式传 capabilities(tools=listChanged false) ##########");
        McpSchema.ServerCapabilities caps = McpSchema.ServerCapabilities.builder()
                .tools(Boolean.FALSE)
                .build();

        McpSyncServer server = McpServer.sync(new StubProvider())
                .serverInfo("probe-explicit", "0.1.0")
                .capabilities(caps)
                .build();
        dump("显式 capabilities:", server.getAsyncServer().getServerCapabilities());
        System.out.println();

        System.out.println("########## C) 运行时 addTool（验证构建期开了 tools 后可否动态增） ##########");
        server.addTool(McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                        .content(List.of(McpSchema.TextContent.builder("pong").build()))
                        .isError(false)
                        .build())
                .build());
        System.out.println("  listTools() = " + server.listTools().stream().map(McpSchema.Tool::name).toList());
        dump("addTool 后 capabilities:", server.getAsyncServer().getServerCapabilities());
        System.out.println();

        System.out.println("########## D) 运行时 removeTool ##########");
        server.removeTool("get_weather");
        System.out.println("  listTools() = " + server.listTools().stream().map(McpSchema.Tool::name).toList());
        dump("removeTool 后 capabilities:", server.getAsyncServer().getServerCapabilities());
        System.out.println();

        System.out.println("  serverInfo = " + server.getAsyncServer().getServerInfo());
        System.out.println();
        System.out.println("PROBE_OK");
    }
}
