package com.example.backend.logging;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class OwnerSubjectHashService {

    private static final String HASH_ALGORITHM = "SHA-256";

    public String hash(String ownerSubject) {
        // なぜ必要か: 監査相関に必要な主体識別を保持しつつ、生の `sub` 値をログへ露出しないため。
        if (ownerSubject == null || ownerSubject.isBlank()) {
            return "anonymous";
        }

        try {
            // なぜ必要か: 同一入力に対して常に同一のハッシュ値を生成し、運用時の検索再現性を担保するため。
            final MessageDigest messageDigest = MessageDigest.getInstance(HASH_ALGORITHM);
            final byte[] digest = messageDigest.digest(ownerSubject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            // なぜ必要か: JDK標準アルゴリズム解決失敗時に監査ログの処理停止を避け、最小限の代替値で継続するため。
            return "hash-unavailable";
        }
    }
}
