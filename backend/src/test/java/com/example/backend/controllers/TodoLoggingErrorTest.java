package com.example.backend.controllers;

import com.example.backend.services.TodoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class TodoLoggingErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TodoService todoService;

    @Test
    void shouldWriteErrorLogForUnhandledException(CapturedOutput output) throws Exception {
        when(todoService.listTodos(anyString(), any(), any(), anyInt(), anyInt(), anyString()))
                .thenThrow(new IllegalStateException("forced-failure-for-test"));

        mockMvc.perform(
                        get("/api/todos")
                                .with(accessToken("owner-error"))
                )
                .andExpect(status().isInternalServerError());

        // なぜ必要か: 未処理例外がERRORログで記録されることを検証し、5xx調査の追跡性を保証するため。
        assertThat(output.getOut())
                .contains("Unhandled exception during request processing")
                .contains("ERROR")
                .contains("UNHANDLED_EXCEPTION");
    }

    private RequestPostProcessor accessToken(String subject) {
        // なぜ必要か: owner_subject 境界で動作するAPI呼び出し条件を実運用相当のJWTで再現するため。
        return jwt().jwt(jwt -> jwt
                .subject(subject)
                .claim("token_use", "access")
                .claim("scope", "openid"));
    }
}
