package probe;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncListFilter;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2 探针：三种形态挂进一个 Spring Boot 应用。
 *
 * <p>两个模式（用 profile 切换）：
 * <ul>
 *   <li><b>naive</b>（默认）：形态二与形态三<b>都</b>挂 {@code POST /mcp} —— 用来复现「后注册者静默失效」。</li>
 *   <li><b>router</b>（{@code --spring.profiles.active=router}）：两个 provider 都配成 {@code /mcp}
 *       （因为 SDK 用 {@code getRequestURI().endsWith(endpoint)} 判路径），
 *       由一个 <b>EraRouterServlet</b> 独占 {@code /mcp}，按 {@code Mcp-Method} 头
 *       直接把请求派发给对应实例的 {@code service()} —— 证明「三形态共存」可落地。</li>
 * </ul>
 */
@SpringBootApplication
@RestController
public class ProbeApp {

    static final String TENANT_HDR = "X-Probe-Tenant";
    static final String MODERN_ERA_HEADER = "Mcp-Method";

    @Autowired
    ServletContext servletContext;

    @Autowired
    org.springframework.core.env.Environment environment;

    public static void main(String[] args) {
        SpringApplication.run(ProbeApp.class, args);
    }

    // ================= 共享的工具集（模拟"管理台配置驱动"） =================

    static McpSchema.Tool tool(String name, String desc) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("q", q);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("additionalProperties", false);
        return McpSchema.Tool.builder(name, schema).description(desc).build();
    }

    /** 只声明 tools，且 listChanged=false（落实 M19）。 */
    static McpSchema.ServerCapabilities caps() {
        return McpSchema.ServerCapabilities.builder().tools(Boolean.FALSE).build();
    }

    /** 逐请求从 HTTP 头取租户，塞进 McpTransportContext。 */
    static McpTransportContextExtractor<HttpServletRequest> extractor() {
        return req -> {
            String t = req.getHeader(TENANT_HDR);
            return t == null ? McpTransportContext.EMPTY : McpTransportContext.create(Map.of("tenant", t));
        };
    }

    /**
     * 只影响「列表能不能看见」。处理器里<b>故意不再校验</b> —— 用来证明"隐藏的工具按名直调仍会执行"。
     * 租户 {@code Z} = 通配（全可见），用于标识"这个响应来自哪个 server 实例"。
     */
    static McpSyncListFilter<McpSchema.Tool> tenantFilter() {
        return (ctx, tool) -> {
            Object t = (ctx == null) ? null : ctx.get("tenant");
            if (t == null) {
                return true;
            }
            String tenant = t.toString();
            if ("A".equals(tenant)) {
                return "tool_alpha".equals(tool.name());
            }
            if ("B".equals(tenant)) {
                return "tool_beta".equals(tool.name());
            }
            return true;
        };
    }

    // ================= 形态一：HTTP + SSE（两模式共用） =================

    @Bean
    ServletRegistrationBean<HttpServletSseServerTransportProvider> sseTransport() {
        var provider = HttpServletSseServerTransportProvider.builder()
                .sseEndpoint("/sse")
                .messageEndpoint("/messages")
                .contextExtractor(extractor())
                .build();

        McpServer.sync(provider)
                .serverInfo("probe-boot", "0.1.0")
                .capabilities(caps())
                .addToolFilter(tenantFilter())
                .toolCall(tool("tool_alpha", "形态一/二共用工具 alpha"), (exchange, request) ->
                        McpSchema.CallToolResult.builder().addTextContent("alpha executed (SSE)").build())
                .toolCall(tool("tool_beta", "形态一/二共用工具 beta"), (exchange, request) ->
                        McpSchema.CallToolResult.builder().addTextContent("beta executed (SSE)").build())
                .build();

        var reg = new ServletRegistrationBean<>(provider, "/sse", "/messages");
        reg.setName("sseProvider");
        return reg;
    }

    // ================= 形态二：Streamable HTTP =================

    /** 造一个"形态二" server（工具 = alpha + beta）。 */
    static HttpServletStreamableServerTransportProvider legacyProvider() {
        var provider = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .contextExtractor(extractor())
                .build();

        McpServer.sync(provider)
                .serverInfo("probe-boot", "0.1.0")
                .capabilities(caps())
                .addToolFilter(tenantFilter())
                .toolCall(tool("tool_alpha", "形态一/二共用工具 alpha"), (exchange, request) ->
                        McpSchema.CallToolResult.builder().addTextContent("alpha executed (Streamable)").build())
                .toolCall(tool("tool_beta", "形态一/二共用工具 beta"), (exchange, request) ->
                        McpSchema.CallToolResult.builder().addTextContent("beta executed (Streamable)").build())
                .build();
        return provider;
    }

    /** naive 模式：形态二直接独占 {@code /mcp}。 */
    @Bean
    @Profile("!router")
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> streamableTransport() {
        var reg = new ServletRegistrationBean<>(legacyProvider(), "/mcp");
        reg.setName("streamableProvider");
        return reg;
    }

    // ================= 形态三：现代无状态 =================

    /** 造一个"形态三" server（工具 = gamma，用来标识响应的来源实例）。 */
    static HttpServletStatelessServerTransport modernTransport() {
        var transport = HttpServletStatelessServerTransport.builder()
                .messageEndpoint("/mcp")
                .contextExtractor(extractor())
                .build();

        McpServer.sync(transport)
                .serverInfo("probe-boot", "0.1.0")
                .capabilities(caps())
                .addToolFilter(tenantFilter())
                .toolCall(tool("tool_gamma", "形态三专用工具 gamma"), (ctx, request) ->
                        McpSchema.CallToolResult.builder().addTextContent("gamma executed (Stateless)").build())
                .build();
        return transport;
    }

    /** naive 模式：形态三<b>也</b>挂 {@code /mcp} —— 故意制造冲突。 */
    @Bean
    @Profile("!router")
    ServletRegistrationBean<HttpServletStatelessServerTransport> statelessTransport() {
        var reg = new ServletRegistrationBean<>(modernTransport(), "/mcp");
        reg.setName("statelessTransport");
        return reg;
    }

    // ================= router 模式：单 Router Servlet 独占 /mcp =================

    @Bean
    @Profile("router")
    ServletRegistrationBean<EraRouterServlet> eraRouter() throws ServletException {
        var legacy = legacyProvider();
        var modern = modernTransport();

        // 手动 init：让 provider 拿到 ServletConfig / ServletContext，
        // 而**不必**把它们另外注册到私有路径（否则会多出无意义的公开映射）。
        legacy.init(new MinimalServletConfig(servletContext, "legacyStreamable"));
        modern.init(new MinimalServletConfig(servletContext, "modernStateless"));

        var reg = new ServletRegistrationBean<>(new EraRouterServlet(legacy, modern), "/mcp");
        reg.setName("eraRouter");
        return reg;
    }

    /** 最小 ServletConfig —— 只为满足 provider 对 config/context 的依赖。 */
    record MinimalServletConfig(ServletContext ctx, String name) implements ServletConfig {
        @Override
        public String getServletName() {
            return name;
        }

        @Override
        public ServletContext getServletContext() {
            return ctx;
        }

        @Override
        public String getInitParameter(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.emptyEnumeration();
        }
    }

    /**
     * era 分流器：按 {@code Mcp-Method} 头判断是不是 {@code 2026-07-28} 现代协议。
     *
     * <p>关键：<b>直接调 {@code service()}</b>（不是 forward）。forward 会保留原
     * {@code getRequestURI()}，而两个 provider 都用 {@code endsWith(endpoint)} 判路径；
     * 直接调用时 {@code getRequestURI()} 是 {@code /mcp}，两边的检查都能过。
     */
    static class EraRouterServlet extends HttpServlet {

        private final HttpServlet legacy;
        private final HttpServlet modern;

        EraRouterServlet(HttpServlet legacy, HttpServlet modern) {
            this.legacy = legacy;
            this.modern = modern;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            String method = req.getHeader(MODERN_ERA_HEADER);
            boolean isModern = (method != null && !method.isBlank());
            System.out.println("[ROUTER] " + req.getMethod() + " " + req.getRequestURI()
                    + "  Mcp-Method=" + method
                    + "  -> " + (isModern ? "MODERN / 形态三 (stateless)" : "LEGACY / 形态二 (streamable)"));
            (isModern ? modern : legacy).service(req, resp);
        }
    }

    // ================= 诊断端点 =================

    @GetMapping("/probe/mappings")
    Map<String, Object> mappings() {
        Map<String, Object> out = new LinkedHashMap<>();
        servletContext.getServletRegistrations().forEach((name, reg) -> {
            if (name.toLowerCase().contains("mcp") || name.contains("Provider")
                    || name.contains("Transport") || name.contains("sse") || name.contains("router")) {
                out.put(name, List.of(reg.getMappings().toArray()));
            }
        });
        return out;
    }

    @GetMapping("/probe/ping")
    Map<String, Object> ping() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("javaVersion", System.getProperty("java.version"));
        out.put("mode", String.join(",", environment.getActiveProfiles()).isEmpty()
                ? "naive" : String.join(",", environment.getActiveProfiles()));
        return out;
    }
}
