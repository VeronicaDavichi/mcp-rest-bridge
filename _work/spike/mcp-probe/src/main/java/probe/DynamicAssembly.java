package probe;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回答主人的问题：「我们的工具全是动态组装的，到底能不能用官方 SDK？」
 *
 * 逐条把「动态组装」的边界跑一遍：
 *   E) 同名工具重复 addTool 会怎样？「更新工具」的正确路径是什么？
 *   F) 复杂 inputSchema（数组/minItems/enum/嵌套对象/anyOf/$defs）是否被原样保留、被 SDK 改写？
 *   G) 管理台配置来的工具名，SDK 的校验规则是什么？（决定控制台侧要怎么校验）
 *   H) 规模：几百个工具时 addTool / listTools 是否还正常。
 */
public class DynamicAssembly {

    static final class StubProvider implements McpServerTransportProvider {
        @Override public void setSessionFactory(McpServerSession.Factory sessionFactory) { }
        @Override public Mono<Void> notifyClients(String method, Object params) {
            System.out.println("      >>> [stub] notifyClients(" + method + ")  被触发！");
            return Mono.empty();
        }
        @Override public Mono<Void> closeGracefully() { return Mono.empty(); }
    }

    /** 只开 tools 能力（不碰 logging —— 那是另一条待拍板的事）。 */
    static McpSyncServer newServer() {
        return McpServer.sync(new StubProvider())
                .serverInfo("probe-dynamic", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(Boolean.FALSE).build())
                .build();
    }

    static McpServerFeatures.SyncToolSpecification spec(McpSchema.Tool tool) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                        .content(List.of(McpSchema.TextContent.builder("ok").build()))
                        .isError(false)
                        .build())
                .build();
    }

    static void attempt(String label, Runnable action) {
        try {
            action.run();
            System.out.println("   " + label + " -> 成功");
        } catch (Throwable t) {
            String msg = String.valueOf(t.getMessage());
            if (msg.length() > 140) msg = msg.substring(0, 140) + "...";
            System.out.println("   " + label + " -> " + t.getClass().getSimpleName() + ": " + msg);
        }
    }

    public static void main(String[] args) {
        McpSyncServer server = newServer();

        System.out.println("########## E) 「更新工具」的正确路径 ##########");
        McpSchema.Tool v1 = McpSchema.Tool.builder("weather",
                Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))))
                .description("v1").build();
        McpSchema.Tool v2 = McpSchema.Tool.builder("weather",
                Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"),
                        "unit", Map.of("type", "string", "enum", List.of("c", "f")))))
                .description("v2").build();

        attempt("首次 addTool(weather)", () -> server.addTool(spec(v1)));
        attempt("同名再 addTool(weather v2)", () -> server.addTool(spec(v2)));
        attempt("removeTool(weather) + addTool(v2) 组合", () -> { server.removeTool("weather"); server.addTool(spec(v2)); });
        System.out.println("   listTools() = " + server.listTools().stream().map(McpSchema.Tool::name).toList());
        System.out.println("   当前描述 = " + server.listTools().get(0).description());
        System.out.println();

        System.out.println("########## F) 复杂 inputSchema 是否被原样保留 ##########");
        Map<String, Object> complex = new LinkedHashMap<>();
        complex.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        complex.put("type", "object");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ids", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1));
        p.put("mode", Map.of("type", "string", "enum", List.of("fast", "slow")));
        p.put("nested", Map.of("type", "object",
                "properties", Map.of("k", Map.of("type", "integer")), "required", List.of("k")));
        p.put("either", Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "integer"))));
        complex.put("properties", p);
        complex.put("required", List.of("ids", "mode"));
        complex.put("additionalProperties", false);

        McpSchema.Tool complexTool = McpSchema.Tool.builder("complex_tool", complex).build();
        Map<String, Object> back = complexTool.inputSchema();
        System.out.println("   传入 keys = " + complex.keySet());
        System.out.println("   回读 keys = " + back.keySet());
        System.out.println("   原样保留 = " + back.equals(complex));
        System.out.println("   回读 properties = " + back.get("properties"));
        System.out.println("   $defs 单独测 = " + McpSchema.Tool.builder("d",
                Map.of("type", "object", "$defs", Map.of("Money", Map.of("type", "object")))).build().inputSchema());
        System.out.println();

        System.out.println("########## G) 工具名校验规则（决定控制台怎么校验） ##########");
        String[] names = { "ok_name-1", "bad name", "中文工具", "a.b", "tool/1", "$x",
                           "x".repeat(200), "", "t".repeat(64), "T" };
        for (String n : names) {
            McpSyncServer s = newServer();
            String show = n.length() > 20 ? n.substring(0, 20) + "…(len=" + n.length() + ")" : "'" + n + "'";
            attempt("addTool(" + show + ")", () -> s.addTool(spec(McpSchema.Tool.builder(n, Map.of("type", "object")).build())));
        }
        System.out.println();

        System.out.println("########## H) 规模：300 个工具 ##########");
        McpSyncServer big = newServer();
        long t0 = System.nanoTime();
        int ok = 0;
        for (int i = 0; i < 300; i++) {
            try {
                big.addTool(spec(McpSchema.Tool.builder("tool_" + i, Map.of("type", "object")).build()));
                ok++;
            } catch (Throwable t) { /* 忽略 */ }
        }
        long t1 = System.nanoTime();
        int listed = big.listTools().size();
        long t2 = System.nanoTime();
        System.out.println("   addTool 成功数 = " + ok + " / 300，耗时 " + ((t1 - t0) / 1_000_000) + " ms");
        System.out.println("   listTools() 条数 = " + listed + "，耗时 " + ((t2 - t1) / 1_000_000) + " ms");
        System.out.println();

        System.out.println("PROBE_OK");
    }
}
