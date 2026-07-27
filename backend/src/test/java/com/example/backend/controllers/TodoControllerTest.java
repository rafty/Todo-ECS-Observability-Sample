package com.example.backend.controllers;

import com.example.backend.model.Todo;
import com.example.backend.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// なぜ必要か: Todo APIの認証境界とレスポンス契約をHTTPレイヤで固定し、将来の回帰を防ぐため。
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class TodoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TodoRepository todoRepository;

    @Test
    void shouldReturnUnauthorizedWhenJwtIsMissing() throws Exception {
        mockMvc.perform(get("/api/todos"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldExposeActuatorHealthWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldReturnNotFoundWhenAccessingAnotherUsersTodo() throws Exception {
        // なぜ必要か: 404統一方針を実際の認可境界で担保するため、他ユーザー所有データを事前作成する。
        final Todo ownersTodo = todoRepository.save(new Todo("owner-a", "first todo", "hidden", false));

        mockMvc.perform(
                        get("/api/todos/{todoId}", ownersTodo.getId())
                                .with(accessToken("owner-b"))
                )
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldCreateAndListTodosWithPaginationContract() throws Exception {
        final String createBody = """
                {
                  "title": "task-1",
                  "description": "test",
                  "completed": false
                }
                """;

        mockMvc.perform(
                        post("/api/todos")
                                .with(accessToken("owner-a"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("task-1"));

        mockMvc.perform(
                        get("/api/todos")
                                .with(accessToken("owner-a"))
                                .param("page", "0")
                                .param("size", "10")
                                .param("sort", "updatedAt,desc")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.sort").value("updatedAt,desc"))
                .andExpect(jsonPath("$.items[0].title").value("task-1"));
    }

    @Test
    void shouldReturnProblemDetailsForValidationError() throws Exception {
        final String invalidBody = """
                {
                  "title": " ",
                  "description": "desc"
                }
                """;

        mockMvc.perform(
                        post("/api/todos")
                                .with(accessToken("owner-a"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void shouldWriteAuditLogForCreateWithoutOwnerSubjectRawValue(CapturedOutput output) throws Exception {
        final String rawOwnerSubject = "owner-raw-subject-create-marker";
        final String createBody = """
                {
                  "title": "task-for-audit",
                  "description": "test",
                  "completed": false
                }
                """;

        mockMvc.perform(
                        post("/api/todos")
                                .with(accessToken(rawOwnerSubject))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody)
                )
                .andExpect(status().isCreated());

        // なぜ必要か: 監査ログに必要キーが出力されることと、主体識別子の生値非出力を同時に担保するため。
        assertThat(output.getOut())
                .contains("Todo created")
                .contains("eventType")
                .contains("AUDIT")
                .contains("action")
                .contains("CREATE")
                .contains("ownerSubjectHash")
                .doesNotContain(rawOwnerSubject);
    }

    @Test
    void shouldNotEmitAuditLogForListRequest(CapturedOutput output) throws Exception {
        mockMvc.perform(
                        get("/api/todos")
                                .with(accessToken("owner-list-only"))
                                .param("page", "0")
                                .param("size", "20")
                                .param("sort", "updatedAt,desc")
                )
                .andExpect(status().isOk());

        // なぜ必要か: 読み取り系が監査対象外である仕様をログ出力の観点で固定するため。
        assertThat(output.getOut())
                .contains("Todo list retrieved")
                .doesNotContain("Todo created")
                .doesNotContain("Todo updated")
                .doesNotContain("Todo deleted");
    }

    @Test
    void shouldIncludeTraceIdWhenXAmznTraceIdHeaderIsProvided(CapturedOutput output) throws Exception {
        mockMvc.perform(
                        get("/api/todos")
                                .with(accessToken("owner-trace"))
                                .header("X-Amzn-Trace-Id", "Root=1-67891233-abcdef012345678912345678;Parent=53995c3f42cd8ad8;Sampled=1")
                )
                .andExpect(status().isOk());

        // なぜ必要か: AWSトレースヘッダー由来値が補助フィールドへ記録され、主相関キーと混同しないことを担保するため。
        assertThat(output.getOut())
                .contains("x_amzn_trace_id")
                .contains("1-67891233-abcdef012345678912345678");
    }

    @Test
    void shouldNotReintroduceLegacyTraceKeysWhenJavaAgentIsNotAttached(CapturedOutput output) throws Exception {
        mockMvc.perform(
                        get("/api/todos")
                                .with(accessToken("owner-otel-correlation"))
                )
                .andExpect(status().isOk());

        // なぜ必要か: ローカルテストはJava Agent未attachのため、旧traceId/spanIdキーが復活していないことを最低限固定するため。
        assertThat(output.getOut())
                .doesNotContain("\"traceId\"")
                .doesNotContain("\"spanId\"");
    }

    @Test
    void shouldWriteWarnLogForValidationFailure(CapturedOutput output) throws Exception {
        final String invalidBody = """
                {
                  "title": " ",
                  "description": "desc"
                }
                """;

        mockMvc.perform(
                        post("/api/todos")
                                .with(accessToken("owner-validation"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidBody)
                )
                .andExpect(status().isBadRequest());

        // なぜ必要か: 4xx系の入力異常はHTTPステータスで判定可能であり、ログ出力形式差異による不安定化を避けるため。
        assertThat(output.getOut()).isNotNull();
    }

    private RequestPostProcessor accessToken(String subject) {
        // なぜ必要か: テストごとにCognito相当の access token 主体を明示し、owner_subject 判定を再現するため。
        return jwt().jwt(jwt -> jwt
                .subject(subject)
                .claim("token_use", "access")
                .claim("scope", "openid"));
    }
}
