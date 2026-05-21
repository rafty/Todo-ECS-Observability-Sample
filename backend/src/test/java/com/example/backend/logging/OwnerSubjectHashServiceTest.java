package com.example.backend.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerSubjectHashServiceTest {

    private final OwnerSubjectHashService ownerSubjectHashService = new OwnerSubjectHashService();

    @Test
    void shouldGenerateDeterministicHashForSameOwnerSubject() {
        final String firstHash = ownerSubjectHashService.hash("owner-subject-1");
        final String secondHash = ownerSubjectHashService.hash("owner-subject-1");

        // なぜ必要か: 監査ログ相関に使う値が入力ごとに安定して再現できることを保証するため。
        assertThat(firstHash)
                .isEqualTo(secondHash)
                .hasSize(64)
                .doesNotContain("owner-subject-1");
    }

    @Test
    void shouldReturnAnonymousWhenOwnerSubjectIsBlank() {
        // なぜ必要か: 主体識別が欠落したケースでもログ出力処理が失敗しないようフォールバック値を固定するため。
        assertThat(ownerSubjectHashService.hash("  ")).isEqualTo("anonymous");
        assertThat(ownerSubjectHashService.hash(null)).isEqualTo("anonymous");
    }
}
