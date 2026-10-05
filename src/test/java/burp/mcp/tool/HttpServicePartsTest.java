package burp.mcp.tool;

import burp.mcp.util.McpError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure service-derivation logic. No Burp runtime needed: these tests cover
 * only URI/Host parsing, never the Montoya factories (which are Burp-bound).
 */
class HttpServicePartsTest {

    @Test
    void partsFromUrl_https_shouldYield443Secure() {
        HttpSendRequestTool.ServiceParts p =
                HttpSendRequestTool.partsFromUrl("https://target.test/app?q=1");
        assertThat(p.host()).isEqualTo("target.test");
        assertThat(p.port()).isEqualTo(443);
        assertThat(p.secure()).isTrue();
    }

    @Test
    void partsFromUrl_http_shouldYield80Plain() {
        HttpSendRequestTool.ServiceParts p =
                HttpSendRequestTool.partsFromUrl("http://target.test:8080/a");
        assertThat(p).isEqualTo(new HttpSendRequestTool.ServiceParts("target.test", 8080, false));
    }

    @Test
    void partsFromUrl_explicitPort_shouldKeepScheme() {
        HttpSendRequestTool.ServiceParts p =
                HttpSendRequestTool.partsFromUrl("https://target.test:8443/a");
        assertThat(p.port()).isEqualTo(8443);
        assertThat(p.secure()).isTrue();
    }

    @Test
    void partsFromUrl_garbage_shouldThrowInvalidParams() {
        assertThatThrownBy(() -> HttpSendRequestTool.partsFromUrl("::::"))
                .isInstanceOf(McpError.class);
    }

    @Test
    void partsFromHostHeader_bare_shouldAssumeHttp() {
        assertThat(HttpSendRequestTool.partsFromHostHeader("target.test"))
                .isEqualTo(new HttpSendRequestTool.ServiceParts("target.test", 80, false));
    }

    @Test
    void partsFromHostHeader_443_shouldImplyHttps() {
        assertThat(HttpSendRequestTool.partsFromHostHeader("target.test:443"))
                .isEqualTo(new HttpSendRequestTool.ServiceParts("target.test", 443, true));
    }

    @Test
    void partsFromHostHeader_customPort_shouldStayPlain() {
        assertThat(HttpSendRequestTool.partsFromHostHeader("target.test:8080"))
                .isEqualTo(new HttpSendRequestTool.ServiceParts("target.test", 8080, false));
    }

    @Test
    void partsFromHostHeader_ipv6_shouldParse() {
        assertThat(HttpSendRequestTool.partsFromHostHeader("[::1]:8080"))
                .isEqualTo(new HttpSendRequestTool.ServiceParts("::1", 8080, false));
        assertThat(HttpSendRequestTool.partsFromHostHeader("::1").port()).isEqualTo(80);
    }

    @Test
    void partsFromHostHeader_badPort_shouldThrowInvalidParams() {
        assertThatThrownBy(() -> HttpSendRequestTool.partsFromHostHeader("target.test:notaport"))
                .isInstanceOf(McpError.class)
                .matches(e -> ((McpError) e).getCode() == McpError.INVALID_PARAMS);
        assertThatThrownBy(() -> HttpSendRequestTool.partsFromHostHeader(":8080"))
                .isInstanceOf(McpError.class);
    }

    @Test
    void parseHttpMode_shouldMapKnownModes() {
        assertThat(HttpSendRequestTool.parseHttpMode(null))
                .isEqualTo(burp.api.montoya.http.HttpMode.AUTO);
        assertThat(HttpSendRequestTool.parseHttpMode("auto"))
                .isEqualTo(burp.api.montoya.http.HttpMode.AUTO);
        assertThat(HttpSendRequestTool.parseHttpMode("http1"))
                .isEqualTo(burp.api.montoya.http.HttpMode.HTTP_1);
        assertThat(HttpSendRequestTool.parseHttpMode("http2"))
                .isEqualTo(burp.api.montoya.http.HttpMode.HTTP_2);
        assertThat(HttpSendRequestTool.parseHttpMode("http2-no-alpn"))
                .isEqualTo(burp.api.montoya.http.HttpMode.HTTP_2_IGNORE_ALPN);
    }

    @Test
    void parseHttpMode_unknown_shouldThrowInvalidParams() {
        assertThatThrownBy(() -> HttpSendRequestTool.parseHttpMode("gopher"))
                .isInstanceOf(McpError.class)
                .matches(e -> ((McpError) e).getCode() == McpError.INVALID_PARAMS);
    }
}
